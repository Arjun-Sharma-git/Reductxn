package io.reductxn.booking.api;

import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.SingleCurrencyObligation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Booking: what a commitment does to the ledger.
 *
 * <p>The two shapes are two methods rather than one method with a nullable quote, because the caller's
 * switch over {@link io.reductxn.planning.api.CycleObligation} is already exhaustive and there is no reason
 * to move that exhaustiveness inside and then hide it behind a default branch.
 *
 * <p><strong>Direction: the debtor is credited and the creditor is debited.</strong> The ledger's
 * convention is that a debit is positive ({@link Posting#signedAmount()}), and what a position means
 * depends on the account's type - so the question is not "who is positive" but "which side of the
 * commitment is a claim". The creditor holds a claim on the debtor, and a claim is an asset, so the
 * creditor is debited. The debtor holds the obligation, which is a liability, so the debtor is credited.
 * {@code Balances.netDebitOf} on the debtor then returns a negative figure, which reads correctly as "this
 * member owes" and is why that method was written to stop at the sign rather than name a balance.
 *
 * <p><strong>Entry ids are derived, not random, and that is what makes a re-booking safe.</strong> An
 * obligation that was recorded and then submitted again - a retried request, a replay of a batch - must not
 * produce a second entry: the ledger is append-only and has no way to take one back (L3). Deriving the id
 * from the obligation's identity means the second attempt produces the same entry id and the same content,
 * so it collides on the primary key and is recognised as a duplicate instead of accumulating a commitment
 * nobody made twice. The client's idempotency key is stored alongside as a second, weaker signal; this one
 * does not depend on the client doing anything.
 *
 * <p>Nothing here reads a clock, a database or a rate. Both methods take the chain head they append to
 * (L4) and the amounts already converted (RULE B19), so a booking is a pure function of its arguments and
 * two calls with the same arguments produce the same bytes.
 */
public final class Bookings {

    /** The role suffixes entry ids are derived from. Distinct, so one commitment's two entries differ. */
    private static final String SINGLE_CURRENCY_ROLE = "obligation";
    private static final String SETTLEMENT_ROLE = "settlement";
    private static final String BASE_ROLE = "base";
    private static final String QUOTE_ROLE = "quote";
    private static final String LINK_ROLE = "fx-link";

    private Bookings() {
    }

    /**
     * Books an ordinary commitment: one entry, debtor to creditor, no conversion and no link.
     *
     * <p>{@code railPerformedFx} is false rather than a parameter. Nothing was converted, so no rail can
     * have performed the conversion, and a flag that is always false here would be a field the caller has
     * to think about for no reason.
     *
     * @param chainHeads the head of the chain this entry appends to, for the obligation's currency
     * @param commitment which commitment this is, whose book, when, and by whom
     * @param obligation the commitment itself: parties and amount
     */
    public static JournalEntry singleCurrency(
            ChainHead chainHeads, Commitment commitment, SingleCurrencyObligation obligation) {

        Objects.requireNonNull(chainHeads, "chainHeads");
        Objects.requireNonNull(commitment, "commitment");
        Objects.requireNonNull(obligation, "obligation");

        requirePostable(commitment.obligationId(), obligation.owed());

        return new JournalEntry(
                entryId(commitment.obligationId(), SINGLE_CURRENCY_ROLE),
                commitment.groupId(),
                obligation.owed().currency(),
                commitment.occurredAt(),
                EntryKind.OBLIGATION,
                commitment.createdBy(),
                commitment.idempotencyKey(),
                null,
                null,
                false,
                List.of(
                        new Posting(obligation.debtorId(), Direction.CREDIT, obligation.owed()),
                        new Posting(obligation.creditorId(), Direction.DEBIT, obligation.owed())),
                chainHeads.hashFor(obligation.owed().currency()));
    }

    /**
     * Books a movement a run has made: one entry, payer to payee, no conversion and no link.
     *
     * <p><strong>It is the mirror of {@link #singleCurrency}, and that is the whole point.</strong> A
     * commitment credits the debtor and debits the creditor - the creditor holds a claim, which is an asset.
     * Discharging it does the opposite: the payer is debited and the payee is credited, so applying a run's
     * transfers to the postings its obligations produced leaves every position flat. That is not a
     * coincidence of the two methods: a run's transfers are computed to zero every participant's position
     * (N2), and they zero the ledger's positions for the same reason.
     *
     * <p><strong>One entry per transfer, and the entry's id is derived from the transfer.</strong> A
     * settlement is not one big entry for a run: each transfer is an independent movement with its own rail
     * reference, and a run that partially fails has moved some of them - so the entries that exist are
     * exactly the movements that happened, and nothing has to be undone when one of them does not (RULE S3,
     * which is why "no silent rollback" is expressible at all).
     *
     * <p>{@code railPerformedFx} is false, and here that is a statement rather than a default: this entry
     * records a transfer the plan produced, and the conversion that produced a cross-currency plan's amounts
     * happened when the quote was locked, not when the money moved. The rail moving currency on a group's
     * behalf is a different fact with its own entry.
     *
     * @param chainHeads the head of the chain this entry appends to, for the transfer's currency
     * @param transfer   the movement: parties, amount, when it was confirmed, and the rail's reference
     */
    public static JournalEntry settlement(ChainHead chainHeads, SettledTransfer transfer) {
        Objects.requireNonNull(chainHeads, "chainHeads");
        Objects.requireNonNull(transfer, "transfer");

        requirePostable(transfer.transferId(), transfer.amount());

        return new JournalEntry(
                entryId(transfer.transferId(), SETTLEMENT_ROLE),
                transfer.groupId(),
                transfer.amount().currency(),
                transfer.occurredAt(),
                EntryKind.SETTLEMENT,
                // No member caused this entry: a rail confirmed it. `createdBy` is for the person who asked
                // for something, and the run that asked is not a member.
                null,
                transfer.railReference(),
                null,
                null,
                false,
                List.of(
                        new Posting(transfer.fromUserId(), Direction.DEBIT, transfer.amount()),
                        new Posting(transfer.toUserId(), Direction.CREDIT, transfer.amount())),
                chainHeads.hashFor(transfer.amount().currency()));
    }

    /**
     * Books a commitment whose creditor is paid in another currency: two linked entries, one per book, with
     * the clearing account as the counterparty of each (ADR 0012 point 3).
     *
     * <p>The legs use different amounts by design: the debtor's side is exact, because that is what they
     * agreed to pay, and the creditor's side is the converted amount, because that is what they agreed to
     * receive. Nothing reconciles them here - the difference is the residual, which {@link Booking} explains
     * at length - and no rate is applied, because the quote arrived already converted (RULE B19).
     *
     * <p>Both legs are chained, to two different chains. A chain is per {@code (group, currency)}, so the
     * base leg appends to the debt currency's chain and the quote leg to the settlement currency's, and
     * whether that is one transaction or two is the caller's decision rather than this method's.
     *
     * @param chainHeads        the heads of both chains, looked up per currency
     * @param commitment        which commitment this is, whose book, when, and by whom
     * @param obligation        the commitment itself: parties, and the locked quote
     * @param clearingAccountId the internal account that makes two single-currency entries expressible
     */
    public static Booking crossCurrency(
            ChainHead chainHeads,
            Commitment commitment,
            CrossCurrencyObligation obligation,
            UUID clearingAccountId) {

        Objects.requireNonNull(chainHeads, "chainHeads");
        Objects.requireNonNull(commitment, "commitment");
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(clearingAccountId, "clearingAccountId");

        Money owed = obligation.owed();
        Money settlement = obligation.settlementAmount();

        // The same refusal libs/planning makes when an obligation names the clearing account as a party, for
        // the same reason: a group cannot owe itself through its internal account. Repeated here because this
        // method takes the id as an argument, so a caller can get it wrong while the obligation is fine.
        if (clearingAccountId.equals(obligation.debtorId())
                || clearingAccountId.equals(obligation.creditorId())) {
            throw new IllegalArgumentException(
                    "the clearing account is the counterparty of a cross-currency booking and cannot also "
                            + "be one of its parties, but it was passed as " + clearingAccountId);
        }

        if (owed.currency().equals(settlement.currency())) {
            throw new IllegalArgumentException(
                    "a cross-currency booking needs two currencies, but both sides are in "
                            + owed.currency().code() + ". A quote that converts a currency into itself is a "
                            + "single-currency obligation carrying one, and linking its two halves would "
                            + "create a link that L9 verifies and no auditor can explain");
        }

        requirePostable(commitment.obligationId(), owed);
        requirePostable(commitment.obligationId(), settlement);

        UUID linkId = linkId(commitment.obligationId());

        JournalEntry base = new JournalEntry(
                entryId(commitment.obligationId(), BASE_ROLE),
                commitment.groupId(),
                owed.currency(),
                commitment.occurredAt(),
                EntryKind.OBLIGATION,
                commitment.createdBy(),
                commitment.idempotencyKey(),
                linkId,
                FxRole.BASE,
                obligation.quote().railPerformedFx(),
                List.of(
                        new Posting(obligation.debtorId(), Direction.CREDIT, owed),
                        new Posting(clearingAccountId, Direction.DEBIT, owed)),
                chainHeads.hashFor(owed.currency()));

        JournalEntry quoteSide = new JournalEntry(
                entryId(commitment.obligationId(), QUOTE_ROLE),
                commitment.groupId(),
                settlement.currency(),
                commitment.occurredAt(),
                EntryKind.OBLIGATION,
                commitment.createdBy(),
                commitment.idempotencyKey(),
                linkId,
                FxRole.QUOTE,
                obligation.quote().railPerformedFx(),
                List.of(
                        new Posting(clearingAccountId, Direction.CREDIT, settlement),
                        new Posting(obligation.creditorId(), Direction.DEBIT, settlement)),
                chainHeads.hashFor(settlement.currency()));

        return new Booking(base, quoteSide, obligation.quote().asConversion());
    }

    /**
     * Refuses an amount that cannot be posted.
     *
     * <p>A posting is a strictly positive integer of minor units (L2), so zero has no entry to make. The case
     * that matters is not a hand-typed zero: a conversion can round a small debt to zero in the creditor's
     * currency, and then the honest answer is to refuse the booking rather than write half a linked pair.
     * L9 would report the base side as unpaired forever, which is worse than a refused request - and "the
     * creditor is owed nothing" is a fact about the commitment, worth saying out loud rather than recording
     * as an absence.
     */
    private static void requirePostable(UUID obligationId, Money amount) {
        if (amount.isZero()) {
            throw new IllegalArgumentException(
                    "obligation " + obligationId + " books an amount of zero " + amount.currency().code()
                            + ", which cannot be posted (L2) and would leave a linked pair with one side "
                            + "missing");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                    "obligation " + obligationId + " books a negative amount in "
                            + amount.currency().code() + ". An obligation's direction is its debtor and "
                            + "creditor, not the sign of its amount");
        }
    }

    /**
     * An entry id derived from the commitment it books, so a re-booking collides instead of duplicating.
     *
     * <p>{@code nameUUIDFromBytes} rather than a random id because it is specified: the same input produces
     * the same UUID in every JVM and every run, which makes the collision a guarantee rather than a
     * coincidence. It is not a security boundary and does not need to be - the input is an identifier nobody
     * is trying to guess, and what it protects is a duplicate commitment, not a secret.
     */
    private static UUID entryId(UUID obligationId, String role) {
        return UUID.nameUUIDFromBytes(qualified(obligationId, role));
    }

    /** The link joining a pair, derived the same way and for the same reason. */
    private static UUID linkId(UUID obligationId) {
        return UUID.nameUUIDFromBytes(qualified(obligationId, LINK_ROLE));
    }

    private static byte[] qualified(UUID obligationId, String role) {
        return (obligationId + ":" + role).getBytes(StandardCharsets.UTF_8);
    }
}
