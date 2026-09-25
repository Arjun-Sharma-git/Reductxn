package io.reductxn.ledger;

import io.reductxn.ledger.api.FxLinkage;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariant L9: the linkage between the two entries of a conversion, verified across chains.
 *
 * <p>Every entry below is individually valid - balanced, single-currency, correctly chained. That is the
 * point of the invariant: a hash chain that verifies in one currency proves nothing about its partner,
 * so pairing has to be checked separately and both directions of it have to be checked.
 */
class FxLinkageTest {

    @Test
    void aBaseAndItsQuoteInOneGroupArePaired() {
        UUID link = Entries.nextLinkId();
        JournalEntry base = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.ALICE, 50_000L, Entries.JPY);
        JournalEntry quote = Entries.conversionSide(
                Entries.GROUP, link, FxRole.QUOTE, Entries.BORIS, 27_952L, Entries.INR);

        assertThat(FxLinkage.unpairedLinkIds(List.of(base, quote)))
                .as("L9: the two entries of a conversion pair on link id and role, and that pair is the "
                        + "only thing joining two hash chains")
                .isEmpty();
    }

    @Test
    void aBaseWithoutItsQuoteIsReported() {
        UUID link = Entries.nextLinkId();
        JournalEntry base = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.ALICE, 50_000L, Entries.JPY);

        assertThat(FxLinkage.unpairedLinkIds(List.of(base)))
                .as("L9: an entry whose partner was never written is valid, chained and balanced on its "
                        + "own - which is exactly why L9 exists separately from L4")
                .containsExactly(link);
    }

    @Test
    void anOrphanQuoteIsReportedToo() {
        UUID link = Entries.nextLinkId();
        JournalEntry quote = Entries.conversionSide(
                Entries.GROUP, link, FxRole.QUOTE, Entries.BORIS, 27_952L, Entries.INR);

        assertThat(FxLinkage.unpairedLinkIds(List.of(quote)))
                .as("L9's wording checks bases, and the check here is symmetric: an orphan quote is the "
                        + "same deletion seen from the other side, so checking one direction would leave "
                        + "a way to remove value by removing the entry that recorded it")
                .containsExactly(link);
    }

    @Test
    void twoBasesForOneLinkAreReported() {
        UUID link = Entries.nextLinkId();
        JournalEntry first = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.ALICE, 50_000L, Entries.JPY);
        JournalEntry second = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.BORIS, 50_000L, Entries.JPY);

        assertThat(FxLinkage.unpairedLinkIds(List.of(first, second)))
                .as("a link with two base sides and no quote is not half-paired - it has no quote side "
                        + "at all, and the role is what makes that distinguishable from a valid pair")
                .containsExactly(link);
    }

    @Test
    void aPairSplitAcrossGroupsIsReported() {
        UUID link = Entries.nextLinkId();
        JournalEntry base = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.ALICE, 50_000L, Entries.JPY);
        JournalEntry quote = Entries.conversionSide(
                Entries.OTHER_GROUP, link, FxRole.QUOTE, Entries.BORIS, 27_952L, Entries.INR);

        assertThat(FxLinkage.unpairedLinkIds(List.of(base, quote)))
                .as("L9 requires the partner to be in the same group, so a link id reused in another "
                        + "book is two halves of nothing rather than a pair")
                .containsExactly(link);
    }

    @Test
    void severalBrokenLinksAreReportedIndependently() {
        UUID paired = Entries.nextLinkId();
        UUID orphaned = Entries.nextLinkId();

        List<JournalEntry> entries = List.of(
                Entries.conversionSide(Entries.GROUP, paired, FxRole.BASE, Entries.ALICE, 1_000L, Entries.JPY),
                Entries.conversionSide(Entries.GROUP, paired, FxRole.QUOTE, Entries.BORIS, 559L, Entries.INR),
                Entries.conversionSide(Entries.GROUP, orphaned, FxRole.BASE, Entries.ALICE, 2_000L, Entries.JPY));

        assertThat(FxLinkage.unpairedLinkIds(entries))
                .as("a verifier reports every finding rather than stopping at the first, because one "
                        + "orphan is a finding and two are two findings")
                .containsExactly(orphaned);
    }

    @Test
    void unlinkedEntriesAreNotTreatedAsUnpaired() {
        assertThat(FxLinkage.unpairedLinkIds(List.of(Entries.transfer(100L, Entries.INR, null))))
                .as("an entry with no link id is not an unpaired one; only a link that exists can fail "
                        + "to pair")
                .isEmpty();
        assertThat(FxLinkage.unpairedLinkIds(List.of()))
                .as("an empty ledger has no broken links")
                .isEmpty();
    }
}
