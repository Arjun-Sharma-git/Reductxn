package io.reductxn.ledger;

import io.reductxn.ledger.api.EntryChain;
import io.reductxn.ledger.api.JournalEntry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant L4 as a verification pass: a chain is checked, not trusted.
 *
 * <p>The tamper cases are built as the threat actually looks - the same row with one field rewritten -
 * rather than as a different entry swapped in, because the interesting question is whether a change that
 * leaves the entry individually valid can be noticed at all. Balancing is preserved in every tamper
 * below, so L1 and L6 still hold and only the chain can see it.
 */
class EntryChainTest {

    @Test
    void anUntouchedChainVerifies() {
        JournalEntry first = Entries.transfer(100L, Entries.INR, null);
        JournalEntry second = Entries.transfer(200L, Entries.INR, first.hash());
        JournalEntry third = Entries.transfer(300L, Entries.INR, second.hash());

        assertThat(EntryChain.problemsIn(List.of(first, second, third)))
                .as("L4: each entry commits to its predecessor's hash, so an untouched chain has no "
                        + "findings")
                .isEmpty();
    }

    @Test
    void aRewrittenEntryIsDetectedByTheEntryAfterIt() {
        JournalEntry first = Entries.transfer(100L, Entries.INR, null);
        JournalEntry second = Entries.transfer(200L, Entries.INR, first.hash());
        JournalEntry third = Entries.transfer(300L, Entries.INR, second.hash());

        // The tamper: the second row is rewritten to move a different amount. It still balances, so L1
        // holds and nothing else would notice - the third entry still commits to the hash the second
        // entry had before, and that is the only place the change becomes visible.
        JournalEntry rewritten = withAmount(second, 999L);

        assertThat(EntryChain.problemsIn(List.of(first, rewritten, third)))
                .as("L4: rewriting an entry breaks the link after it, which is why verification exists "
                        + "rather than trusting the stored hashes")
                .hasSize(1)
                .allSatisfy(problem -> assertThat(problem).contains(third.id().toString()));
    }

    @Test
    void aStartThatReferencesAPredecessorIsReported() {
        JournalEntry orphan = Entries.transfer(100L, Entries.INR, "00");

        assertThat(EntryChain.problemsIn(List.of(orphan)))
                .as("an entry claiming a predecessor that was not supplied means the chain being checked "
                        + "is incomplete, so a clean result would be a false reassurance")
                .hasSize(1)
                .allSatisfy(problem -> assertThat(problem).contains("missing"));
    }

    @Test
    void anEntryFromAnotherCurrencyIsReportedAsBeingInTheWrongChain() {
        JournalEntry inr = Entries.transfer(100L, Entries.INR, null);
        JournalEntry jpy = Entries.transfer(100L, Entries.JPY, inr.hash());

        assertThat(EntryChain.problemsIn(List.of(inr, jpy)))
                .as("chains are per group and currency, so a conversion is two chains joined by "
                        + "fx_link_id - an entry from another book is in the wrong chain, not merely "
                        + "misordered")
                .hasSize(1)
                .allSatisfy(problem -> assertThat(problem).contains("JPY"));
    }

    @Test
    void chainOrderIsTheCallersClaimAndIsChecked() {
        JournalEntry first = Entries.transfer(100L, Entries.INR, null);
        JournalEntry second = Entries.transfer(200L, Entries.INR, first.hash());

        assertThat(EntryChain.problemsIn(List.of(second, first)))
                .as("supplying a chain in the wrong order is reported rather than silently accepted, "
                        + "because order is the caller's knowledge and not something the model can infer")
                .isNotEmpty();
    }

    @Test
    void headHashIsAbsentForTheFirstEntryAndTheLastHashAfterwards() {
        JournalEntry first = Entries.transfer(100L, Entries.INR, null);
        JournalEntry second = Entries.transfer(200L, Entries.INR, first.hash());

        assertThat(EntryChain.problemsIn(List.of()))
                .as("an empty chain is not a broken one - a group with no entries yet is normal")
                .isEmpty();
        assertThat(EntryChain.headHash(List.of()))
                .as("the first entry in a chain references nothing, so the genesis case is null rather "
                        + "than a hash of zeroes that would never match")
                .isNull();
        assertThat(EntryChain.headHash(List.of(first, second))).isEqualTo(second.hash());
    }

    /** The same entry with a different balanced amount: what a rewritten row looks like. */
    private static JournalEntry withAmount(JournalEntry entry, long amountMinor) {
        return new JournalEntry(
                entry.id(), entry.groupId(), entry.currency(), entry.occurredAt(), entry.kind(),
                entry.createdBy(), entry.idempotencyKey(), entry.fxLinkId(), entry.fxRole(),
                entry.railPerformedFx(),
                List.of(Entries.debit(Entries.ALICE, amountMinor, entry.currency()),
                        Entries.credit(Entries.BORIS, amountMinor, entry.currency())),
                entry.prevHash());
    }
}
