package io.reductxn.api.application.obligations;

import io.reductxn.api.port.in.NewObligation;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.RecordObligation;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.out.ChainHeads;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.api.port.out.FxQuotes;
import io.reductxn.api.port.out.JournalEntries;
import io.reductxn.api.port.out.ObligationStore;
import io.reductxn.api.port.out.ObligationToRecord;
import io.reductxn.booking.api.Booking;
import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.Commitment;
import io.reductxn.fx.api.FxException;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.FxRateProvider;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SingleCurrencyObligation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: record what a member promised, and book what that does to the ledger.
 *
 * <p><strong>It contains no rules.</strong> Which entry a commitment produces is {@code libs/booking}'s
 * decision, which entries a cycle produces is {@code libs/planning}'s, and what a rate does to an amount is
 * {@code libs/fx}'s. What is here is the sequence - check the request, resolve the currencies, ask for the
 * chain heads, book, append - plus the transaction that makes the chain heads meaningful, which RULE B15
 * keeps in this layer.
 *
 * <p><strong>This is the first write path in the product, and the first place a {@code Result} carries a
 * refusal out to a status.</strong> The order of operations is chosen so a refused request writes nothing:
 * every refusal is decided before the obligation row is inserted, and the row is inserted before the entry
 * is appended, so a duplicate key - the one thing a read-then-write replay cannot see coming - aborts the
 * transaction before anything is booked.
 *
 * <p><strong>{@code settleInCurrency} works when a rate is stored, and fails closed when none is.</strong> A rate
 * comes from {@link FxRateProvider}, which reads {@code fx_rates}; since ADR 0027 that table is filled by
 * {@code ScheduledRateIngestion} from a provider's document, so a deployment with a feed converts and one without
 * it answers {@code 503} with {@code fx.rate_unavailable} - what MASTER-PLAN section 4 means by "no rate means no
 * cross-currency". What this class deliberately does not do is book the pair when there is no rate: a
 * cross-currency obligation without its quote has nowhere to put the conversion, and recording one would store its
 * debt side only - which the netting preview would then read back as a single-currency obligation, silently, in the
 * currency the creditor is not being paid in.
 *
 * <p>Nothing here reads a clock: {@code occurredAt} arrives decided (RULE B21), which is what makes a request
 * for a past instant meaningful and a test reproducible.
 */
@Service
public class RecordObligationService implements RecordObligation {

    private final ObligationStore obligations;
    private final JournalEntries entries;
    private final ChainHeads chainHeads;
    private final CurrencyCatalog currencies;
    private final FxRateProvider rates;
    private final FxQuotes quotes;

    public RecordObligationService(
            ObligationStore obligations,
            JournalEntries entries,
            ChainHeads chainHeads,
            CurrencyCatalog currencies,
            FxRateProvider rates,
            FxQuotes quotes) {

        this.obligations = Objects.requireNonNull(obligations, "obligations");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.chainHeads = Objects.requireNonNull(chainHeads, "chainHeads");
        this.currencies = Objects.requireNonNull(currencies, "currencies");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.quotes = Objects.requireNonNull(quotes, "quotes");
    }

    @Override
    @Transactional
    public Result<RecordedObligation> record(NewObligation request) {
        Objects.requireNonNull(request, "request");

        // The two refusals that are about the request being coherent, before anything is read or written.
        // Checked here rather than left to the domain types, whose guards throw: from their side a member
        // owing themselves means the code that built the obligation is wrong, and from this side it means the
        // client is.
        if (request.debtorId().equals(request.creditorId())) {
            return Result.fail(new ObligationRefusals.SameParty(request.debtorId()));
        }
        if (request.amountMinor() <= 0L) {
            return Result.fail(
                    new ObligationRefusals.AmountNotPositive(request.amountMinor(), request.currency()));
        }

        // A replay is answered with what is on the record, and books nothing. Checked before the currencies
        // are resolved, because a key that has been used identifies an obligation rather than a request: the
        // client is asking what happened, and it happened.
        Optional<RecordedObligation> already = obligations.findExisting(
                request.groupId(), request.idempotencyKey());

        if (already.isPresent()) {
            return Result.ok(already.get());
        }

        Currency debtCurrency = currencies.byCode(request.currency()).orElse(null);
        if (debtCurrency == null) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(request.currency()));
        }

        UUID obligationId = UUID.randomUUID();
        Commitment commitment = new Commitment(
                obligationId, request.groupId(), request.occurredAt(), request.idempotencyKey(),
                // No identity module yet, so nothing can say who submitted it. Null means "system-generated"
                // on the entry; a made-up id would be a claim about a member who does not exist.
                null);

        // Everything below uses `commitment.occurredAt()` rather than the request's instant. The commitment
        // truncates it to what the ledger can store, so the row, the entry and the response all carry the
        // value that was actually recorded - and a replayed request, whose response is read back from the
        // row, answers with the same instant rather than one that differs in the last few digits.
        return request.isCrossCurrency()
                ? crossCurrency(request, commitment, debtCurrency)
                : singleCurrency(request, commitment, debtCurrency);
    }
    /**
     * The ordinary case: one entry, debtor to creditor, in one currency.
     *
     * <p>{@code Bookings} is called statically rather than injected, unlike the planner in the preview's use
     * case. The planner is a bean because it is a choice between algorithms (ADR 0012); booking is a pure
     * function with one implementation, and a bean for it would be wiring that decides nothing.
     */
    private Result<RecordedObligation> singleCurrency(
            NewObligation request, Commitment commitment, Currency currency) {

        Money amount = Money.of(request.amountMinor(), currency);

        obligations.record(new ObligationToRecord(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                amount, null, request.memo(), request.idempotencyKey(), commitment.occurredAt()));

        JournalEntry entry = Bookings.singleCurrency(
                chainHeads.forGroup(request.groupId()),
                commitment,
                new SingleCurrencyObligation(request.debtorId(), request.creditorId(), amount));

        entries.append(entry);

        return Result.ok(new RecordedObligation(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                amount, currency, null, request.memo(), ObligationStatus.OPEN, commitment.occurredAt()));
    }

    /**
     * The cross-currency case: lock a rate to the amount, store what was locked, and book the linked pair.
     *
     * <p>The port's failures are exceptions that implement {@code DomainError}, and they are caught and
     * returned as a value. That is not defensive coding: this module's {@code DomainErrorResolver} reads a
     * {@code DomainError} that escapes to the boundary as a programming fault and answers {@code 500} - right
     * for an invariant that was broken, wrong for a provider that could not be reached. Catching here is where
     * those two readings are separated, and it is why the resolver did not have to be loosened.
     *
     * <p><strong>The order of the three writes is the point.</strong> The quote, then the obligation that
     * points at it, then the entries: each step depends on the one before it, so a failure leaves no
     * half-recorded conversion rather than a debt with no promise attached. The caller's transaction is what
     * makes that true rather than merely likely - and it is the same transaction the chain locks were taken
     * in, which matters twice here because a cross-currency commitment appends to two chains.
     *
     * <p>{@code railPerformedFx} is false and will stay false until the run. At recording time no rail has
     * done anything; the flag becomes meaningful when value actually moves, and setting it here would claim a
     * conversion nobody has performed yet.
     */
    private Result<RecordedObligation> crossCurrency(
            NewObligation request, Commitment commitment, Currency debtCurrency) {

        Currency settlementCurrency = currencies.byCode(request.settleInCurrency()).orElse(null);

        if (settlementCurrency == null) {
            return Result.fail(new ObligationRefusals.UnknownCurrency(request.settleInCurrency()));
        }

        Money owed = Money.of(request.amountMinor(), debtCurrency);

        FxQuote quote;
        try {
            FxRate rate = rates.rateFor(debtCurrency, settlementCurrency, commitment.occurredAt());
            quote = FxQuote.lock(
                    quoteIdOf(commitment.obligationId()), owed, rate, commitment.occurredAt(), false);
        } catch (FxException expected) {
            // The provider's or the lock's own code and message, not a rewording: the client branches on the
            // code, and those two are the only places that know whether the rate was stale or absent.
            return Result.fail(expected);
        }

        CrossCurrencyObligation obligation = new CrossCurrencyObligation(
                request.debtorId(), request.creditorId(), quote);

        quotes.record(quote);

        obligations.record(new ObligationToRecord(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                owed, quote.id(), request.memo(), request.idempotencyKey(), commitment.occurredAt()));

        Booking booking = Bookings.crossCurrency(
                chainHeads.forGroup(request.groupId()), commitment, obligation, FxClearing.ACCOUNT_ID);

        for (JournalEntry entry : booking.entries()) {
            entries.append(entry);
        }

        return Result.ok(new RecordedObligation(
                commitment.obligationId(), request.groupId(), request.debtorId(), request.creditorId(),
                owed, obligation.settlementCurrency(), quote, request.memo(), ObligationStatus.OPEN,
                commitment.occurredAt()));
    }

    /**
     * The quote's identity, derived from the commitment it belongs to.
     *
     * <p>{@code FxQuote} takes its id from the caller "so this module stays deterministic", and the commitment
     * is what the id has to be derived from: a quote is part of one commitment's history, not an independently
     * addressable conversion. The scheme mirrors {@code libs/booking}'s entry ids deliberately - same
     * technique, same reason - so that a re-run of the same commitment produces the same quote id and collides
     * rather than locking a second rate for a commitment nobody made twice.
     */
    private static UUID quoteIdOf(UUID obligationId) {
        return UUID.nameUUIDFromBytes((obligationId + ":fx-quote").getBytes(StandardCharsets.UTF_8));
    }

}
