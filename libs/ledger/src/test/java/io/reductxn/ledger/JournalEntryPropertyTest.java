package io.reductxn.ledger;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger invariants that hold over generated entries: L1, L2, L4 and L6.
 *
 * <p><strong>Scope is deliberate.</strong> L3 and L5 are properties of the schema and the persistence
 * layer - {@code no UPDATE grant} and {@code no stored balance} - and a test in a pure module cannot
 * make a claim about either. L9 is asserted in {@code FxLinkageTest}, L7 where a rate is visible, and L8
 * as far as a measurement exists. The deferrals in {@code PropertyCoverageTest} say the same thing in
 * the same words, so there is one story rather than two.
 *
 * <p>L1 is asserted in its literal form - total debits equal total credits - rather than only as "nets
 * to zero", because zero is also what a mixed-currency entry nets to when the two amounts happen to be
 * equal integers. That is precisely the case L6 exists to refuse, and asserting only the net would let
 * the two invariants blur into one.
 */
class JournalEntryPropertyTest {

    private static final List<Currency> CURRENCIES = List.of(Entries.INR, Entries.JPY, Entries.TND);

    /** Bounded so that generated totals cannot overflow when summed or doubled. */
    @Provide
    Arbitrary<Long> debitAmounts() {
        return Arbitraries.longs().between(1L, 400_000L);
    }

    /** One to three debits, so generated entries exercise more than the minimal two-posting shape. */
    @Provide
    Arbitrary<List<Long>> debitAmountLists() {
        return debitAmounts().list().ofMinSize(1).ofMaxSize(3);
    }

    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of(CURRENCIES);
    }

    /** Amounts that exercise the boundary of L2: zero and negatives are generated on purpose. */
    @Provide
    Arbitrary<Long> anyAmounts() {
        return Arbitraries.longs().between(-1_000L, 1_000L);
    }

    /**
     * A balanced entry of one to four postings: one or more debits, and a single credit of their total.
     *
     * <p>Built this way rather than by generating both sides independently and filtering for equality,
     * which is the usual attempt and is wrong twice over - the filter rejects most inputs, and jqwik
     * cannot shrink through a rejection to a minimal counterexample.
     */
    @Provide
    Arbitrary<JournalEntry> entries() {
        return Combinators.combine(currencies(), debitAmountLists())
                .as(JournalEntryPropertyTest::balancedEntry);
    }

    @Property
    void aBalancedEntryHasEqualDebitsAndCreditsInItsCurrency(
            @ForAll("currencies") Currency currency, @ForAll("debitAmountLists") List<Long> debits) {

        JournalEntry entry = balancedEntry(currency, debits);

        assertThat(totalOf(entry, Direction.DEBIT))
                .as("L1: total debits equal total credits, per currency, for every entry")
                .isEqualTo(totalOf(entry, Direction.CREDIT))
                .isEqualTo(debits.stream().mapToLong(Long::longValue).sum());
    }

    @Property
    void anUnbalancedEntryIsAlwaysRefused(
            @ForAll("currencies") Currency currency,
            @ForAll("debitAmountLists") List<Long> debits,
            @ForAll("debitAmounts") long mismatch) {

        List<Posting> postings = new ArrayList<>();
        int index = 0;
        long total = 0L;
        for (long amount : debits) {
            postings.add(Entries.debit(account(index++), amount, currency));
            total += amount;
        }
        postings.add(Entries.credit(account(index), total + mismatch, currency));

        assertThatThrownBy(() -> entry(currency, postings))
                .as("L1: an entry off by even one minor unit is refused, because nothing downstream could "
                        + "tell which side of it was the mistake")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void everySingleFieldChangeChangesTheHash(@ForAll("entries") JournalEntry entry) {
        JournalEntry later = copy(entry, entry.kind(), entry.occurredAt().plusSeconds(1L),
                entry.prevHash(), entry.postings());
        JournalEntry otherKind = copy(entry, differentKind(entry.kind()), entry.occurredAt(),
                entry.prevHash(), entry.postings());
        JournalEntry chained = copy(entry, entry.kind(), entry.occurredAt(),
                entry.prevHash() == null ? "x" : entry.prevHash() + "x", entry.postings());
        JournalEntry scaled = copy(entry, entry.kind(), entry.occurredAt(), entry.prevHash(),
                scaled(entry.postings(), 2L));
        JournalEntry reordered = copy(entry, entry.kind(), entry.occurredAt(), entry.prevHash(),
                reversed(entry.postings()));

        assertThat(Set.of(entry.hash(), later.hash(), otherKind.hash(), chained.hash(),
                        scaled.hash(), reordered.hash()))
                .as("L4: the digest covers every field and the order of the postings, so no single "
                        + "change to an entry can leave its chain verifying")
                .hasSize(6);
    }

    @Property
    void equalContentHashesEqually(
            @ForAll("currencies") Currency currency, @ForAll("debitAmountLists") List<Long> debits) {

        JournalEntry first = balancedEntry(currency, debits);
        JournalEntry sameContent = copy(
                first, first.kind(), first.occurredAt(), first.prevHash(), first.postings());

        assertThat(first).isEqualTo(sameContent);
        assertThat(first.hash())
                .as("L4: the hash is a pure function of content, so recomputing it from the same rows "
                        + "gives the same value - which is the entire basis of an offline verifier")
                .isEqualTo(sameContent.hash());
    }

    @Property
    void everyPostingIsInTheEntrysCurrency(@ForAll("entries") JournalEntry entry) {
        assertThat(entry.postings())
                .as("L6: one currency per entry, which is what keeps L1 a single sum rather than a sum "
                        + "per currency and keeps both expressible as database constraints")
                .allSatisfy(posting -> assertThat(posting.currency()).isEqualTo(entry.currency()));
    }

    @Property
    void anEntryThatBalancesOnlyBecauseItsCurrenciesDifferIsRefused(
            @ForAll("currencies") Currency currency, @ForAll("debitAmounts") long amount) {

        Currency other = CURRENCIES.get((CURRENCIES.indexOf(currency) + 1) % CURRENCIES.size());

        assertThatThrownBy(() -> entry(currency, List.of(
                Entries.debit(account(0), amount, currency),
                Entries.credit(account(1), amount, other))))
                .as("L6: the same integer on both sides balances the raw minors while mixing "
                        + currency.code() + " and " + other.code()
                        + "; the ledger refuses it rather than calling that balanced")
                .isInstanceOf(CurrencyMismatchException.class);
    }

    private static long totalOf(JournalEntry entry, Direction direction) {
        return entry.postings().stream()
                .filter(posting -> posting.direction() == direction)
                .mapToLong(posting -> posting.amount().amountMinor())
                .sum();
    }

    private static JournalEntry balancedEntry(Currency currency, List<Long> debits) {
        List<Posting> postings = new ArrayList<>();
        int index = 0;
        long total = 0L;
        for (long amount : debits) {
            postings.add(Entries.debit(account(index++), amount, currency));
            total += amount;
        }
        postings.add(Entries.credit(account(index), total, currency));
        return entry(currency, postings);
    }

    private static JournalEntry entry(Currency currency, List<Posting> postings) {
        return new JournalEntry(
                Entries.nextId(), Entries.GROUP, currency, Entries.WHEN, EntryKind.OBLIGATION,
                null, null, null, null, false, postings, null);
    }

    /** The same entry with the four components the hash properties vary. */
    private static JournalEntry copy(
            JournalEntry entry, EntryKind kind, Instant occurredAt, String prevHash,
            List<Posting> postings) {

        return new JournalEntry(entry.id(), entry.groupId(), entry.currency(), occurredAt, kind,
                entry.createdBy(), entry.idempotencyKey(), entry.fxLinkId(), entry.fxRole(),
                entry.railPerformedFx(), postings, prevHash);
    }

    /** Always a different kind, whichever one the generated entry has. */
    private static EntryKind differentKind(EntryKind kind) {
        return kind == EntryKind.TAX ? EntryKind.FEE : EntryKind.TAX;
    }

    /** Scaling every posting keeps the entry balanced while changing its content. */
    private static List<Posting> scaled(List<Posting> postings, long factor) {
        return postings.stream()
                .map(posting -> new Posting(posting.accountId(), posting.direction(),
                        posting.amount().scaledBy(factor)))
                .toList();
    }

    /** Reordering keeps the sums identical, so only a hash that covers posting order can notice. */
    private static List<Posting> reversed(List<Posting> postings) {
        List<Posting> reversed = new ArrayList<>(postings);
        Collections.reverse(reversed);
        return reversed;
    }

    /** Distinct accounts by construction, so generated postings are never accidentally identical. */
    private static UUID account(int index) {
        return new UUID(0L, 100L + index);
    }
}
