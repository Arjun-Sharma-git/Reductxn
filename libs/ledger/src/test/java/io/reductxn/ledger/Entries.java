package io.reductxn.ledger;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fixtures for the ledger tests: one place where a valid entry of each shape is built.
 *
 * <p>Exists because an entry has twelve components, so building one inline in eight tests would bury
 * what each test is actually about - and because a test that has to spell out all twelve is a test
 * nobody will extend.
 *
 * <p>Ids come from {@link #nextId()} rather than being random, so a failing unit test reproduces
 * exactly. The property tests generate their own ids instead, so that a regenerated seed produces the
 * same entries rather than depending on how many tests ran first.
 */
final class Entries {

    /** Exponent 2, 0 and 3: an exponent shift that is genuinely exercised rather than uniform. */
    static final Currency INR = Currency.of("INR", 2);
    static final Currency JPY = Currency.of("JPY", 0);
    static final Currency TND = Currency.of("TND", 3);

    static final UUID GROUP = new UUID(0L, 1L);
    static final UUID OTHER_GROUP = new UUID(0L, 2L);
    static final UUID ALICE = new UUID(0L, 10L);
    static final UUID BORIS = new UUID(0L, 11L);

    /** Stands in for {@code libs/planning}'s fx_clearing account, which this module cannot see (B2). */
    static final UUID CLEARING = new UUID(0L, 12L);

    static final Instant WHEN = Instant.parse("2026-09-25T10:00:00Z");

    private static long sequence = 0L;

    private Entries() {
    }

    static UUID nextId() {
        sequence++;
        return new UUID(0L, 1_000L + sequence);
    }

    static UUID nextLinkId() {
        sequence++;
        return new UUID(0L, 2_000L + sequence);
    }

    /** A balanced two-posting entry: {@code from} debits the amount and {@code to} credits it. */
    static JournalEntry transfer(long amountMinor, Currency currency, String prevHash) {
        return transfer(ALICE, BORIS, amountMinor, currency, prevHash);
    }

    static JournalEntry transfer(UUID from, UUID to, long amountMinor, Currency currency, String prevHash) {
        return new JournalEntry(
                nextId(), GROUP, currency, WHEN, EntryKind.OBLIGATION, null, null, null, null, false,
                List.of(debit(from, amountMinor, currency), credit(to, amountMinor, currency)),
                prevHash);
    }

    /**
     * One side of a conversion: an ordinary balanced entry that also carries a link and a role.
     *
     * <p>Mirrors ADR 0012's worked example, where each side moves value between a member and the clearing
     * account rather than between two members. No {@code prevHash} parameter, because the linkage is
     * verified across chains and is independent of where the entry sits in one.
     */
    static JournalEntry conversionSide(
            UUID groupId, UUID linkId, FxRole role, UUID member, long amountMinor, Currency currency) {

        // ADR 0012's worked example, where a positive figure is value received:
        //   JPY book: Alice -50,000   fx_clearing +50,000   (the debtor gives up the debt currency)
        //   INR book: fx_clearing -27,952   Boris +27,952   (clearing pays the creditor)
        // So clearing receives on the base side and pays on the quote side.
        UUID debit = role == FxRole.BASE ? CLEARING : member;
        UUID credit = role == FxRole.BASE ? member : CLEARING;

        return new JournalEntry(
                nextId(), groupId, currency, WHEN, EntryKind.OBLIGATION, null, null, linkId, role, false,
                List.of(debit(debit, amountMinor, currency), credit(credit, amountMinor, currency)),
                null);
    }

    /** A compensating entry: the same postings with both directions swapped (L3). */
    static JournalEntry reversalOf(JournalEntry original, String prevHash) {
        List<Posting> mirrored = original.postings().stream()
                .map(posting -> new Posting(
                        posting.accountId(),
                        posting.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT,
                        posting.amount()))
                .toList();

        return new JournalEntry(
                nextId(), original.groupId(), original.currency(), WHEN, EntryKind.ADJUSTMENT,
                null, null, null, null, false, mirrored, prevHash);
    }

    static Posting debit(UUID accountId, long amountMinor, Currency currency) {
        return new Posting(accountId, Direction.DEBIT, Money.of(amountMinor, currency));
    }

    static Posting credit(UUID accountId, long amountMinor, Currency currency) {
        return new Posting(accountId, Direction.CREDIT, Money.of(amountMinor, currency));
    }
}
