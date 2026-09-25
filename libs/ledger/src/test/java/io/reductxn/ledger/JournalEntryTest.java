package io.reductxn.ledger;

import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;
import io.reductxn.ledger.internal.EntryHash;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structural invariants an entry enforces on construction: L1, L6, and the FX linkage pairing.
 *
 * <p>The point of these tests is that the invariants are <em>properties of the model</em> rather than
 * rules a caller remembers. Where a guard is refused, the refusal is asserted - a check that silently
 * stopped firing would otherwise leave every other test passing.
 */
class JournalEntryTest {

    @Test
    void balancedEntryIsAcceptedAndNetsToZero() {
        JournalEntry entry = Entries.transfer(50_000L, Entries.JPY, null);

        assertThat(netOf(entry))
                .as("L1: debits equal credits in the entry's currency, so the entry nets to zero")
                .isZero();
        assertThat(entry.postings()).hasSize(2);
        assertThat(entry.currency()).isEqualTo(Entries.JPY);
    }

    @Test
    void unbalancedEntryIsRefused() {
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Instant.now(), EntryKind.OBLIGATION,
                null, null, null, null, false,
                List.of(Entries.debit(Entries.ALICE, 100L, Entries.INR),
                        Entries.credit(Entries.BORIS, 99L, Entries.INR)),
                null))
                .as("L1: an entry that does not balance would create one minor unit from nothing, and "
                        + "nothing downstream could tell which side was wrong")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L1");
    }

    @Test
    void entryThatBalancesOnlyIfCurrenciesAreMixedIsRefused() {
        // 100 paise against 100 yen nets to zero in the raw integers, and to nothing at all in reality.
        // This is why L6 is checked before L1 and why L6 is intact (ADR 0012).
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Instant.now(), EntryKind.OBLIGATION,
                null, null, null, null, false,
                List.of(Entries.debit(Entries.ALICE, 100L, Entries.INR),
                        Entries.credit(Entries.BORIS, 100L, Entries.JPY)),
                null))
                .as("L6: every posting in an entry is in the entry's currency, so a cross-currency "
                        + "obligation is two linked entries rather than one mixed one")
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void entryNeedsAtLeastTwoPostings() {
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Instant.now(), EntryKind.OBLIGATION,
                null, null, null, null, false,
                List.of(Entries.debit(Entries.ALICE, 1L, Entries.INR)),
                null))
                .as("a single posting cannot be double entry - it would have to be both a debit and a "
                        + "credit, which is not a thing the model can say")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Instant.now(), EntryKind.OBLIGATION,
                null, null, null, null, false, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linkageFieldsAreAPairRatherThanTwoIndependentColumns() {
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Entries.WHEN, EntryKind.OBLIGATION,
                null, null, Entries.nextLinkId(), null, false,
                List.of(Entries.debit(Entries.ALICE, 1L, Entries.INR),
                        Entries.credit(Entries.CLEARING, 1L, Entries.INR)),
                null))
                .as("L9: a link id without a role can find the partner but cannot say which side this "
                        + "entry is, so the pair is unverifiable")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("L9");

        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Entries.WHEN, EntryKind.OBLIGATION,
                null, null, null, FxRole.BASE, false,
                List.of(Entries.debit(Entries.ALICE, 1L, Entries.INR),
                        Entries.credit(Entries.CLEARING, 1L, Entries.INR)),
                null))
                .as("L9: a role without a link id has nothing to be paired with")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankIdempotencyKeyIsRefusedButAnAbsentOneIsFine() {
        assertThatThrownBy(() -> new JournalEntry(
                UUID.randomUUID(), Entries.GROUP, Entries.INR, Entries.WHEN, EntryKind.OBLIGATION,
                null, "  ", null, null, false,
                List.of(Entries.debit(Entries.ALICE, 1L, Entries.INR),
                        Entries.credit(Entries.BORIS, 1L, Entries.INR)),
                null))
                .as("a blank idempotency key looks set while matching every other blank key, which is "
                        + "worse than not having one because a retry would be treated as already applied")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Entries.transfer(1L, Entries.INR, null).idempotencyKey()).isNull();
    }

    @Test
    void postingsCannotBeMutatedAfterConstruction() {
        JournalEntry entry = Entries.transfer(10L, Entries.INR, null);

        assertThatThrownBy(() -> entry.postings().clear())
                .as("the entry holds an unmodifiable copy, so a caller holding the list it passed in "
                        + "cannot reach back into a stored entry and change it - which is the model half "
                        + "of append-only, with the database grants making it final")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(entry.postings()).hasSize(2);
    }

    @Test
    void aCorrectionIsACompensatingEntryRatherThanAnEdit() {
        JournalEntry original = Entries.transfer(250L, Entries.INR, null);
        JournalEntry reversal = Entries.reversalOf(original, original.hash());

        List<Posting> both = new ArrayList<>(original.postings());
        both.addAll(reversal.postings());

        assertThat(Balances.netDebitOf(Entries.ALICE, Entries.INR, both).amountMinor())
                .as("nothing can change an entry, so a correction is a second entry that cancels the "
                        + "first - the account returns to zero rather than the mistake disappearing")
                .isZero();
        assertThat(reversal.postings()).hasSize(original.postings().size());
    }

    @Test
    void theHashIsLowercaseHexOfTheDeclaredLength() {
        String hash = Entries.transfer(42L, Entries.INR, null).hash();

        assertThat(hash)
                .as("L4: the hash is the SHA-256 digest rendered as hex, so a stored value is 64 "
                        + "characters and compares as text")
                .hasSize(EntryHash.hexLength())
                .matches("[0-9a-f]{64}");
    }

    @Test
    void equalContentHashesEquallyAndThePreviousHashChangesIt() {
        UUID id = Entries.nextId();
        JournalEntry first = entryWith(id, null, null);
        JournalEntry again = entryWith(id, null, null);
        JournalEntry chained = entryWith(id, null, "00");

        assertThat(first).isEqualTo(again);
        assertThat(first.hash())
                .as("L4: the hash is a pure function of the content, so the same entry stored twice "
                        + "verifies twice")
                .isEqualTo(again.hash());
        assertThat(chained.hash())
                .as("L4: prev_hash is an input to the digest, so the same entry in two positions in a "
                        + "chain is two different hashes - which is what makes a reorder detectable")
                .isNotEqualTo(first.hash());
    }

    @Test
    void theCanonicalEncodingNamesItsVersionAndLengthPrefixesFreeFormValues() {
        String canonical = EntryHash.canonicalForm(entryWith(Entries.nextId(), null, null));

        assertThat(canonical)
                .as("the encoding names its own version, because a stored hash cannot say which "
                        + "encoding produced it and a silent format change would invalidate every chain")
                .startsWith("reductxn.ledger.entry.v1\n");
        assertThat(canonical)
                .as("free-form values carry a length, so a value cannot impersonate a field boundary "
                        + "and a collision cannot be constructed rather than searched for")
                .contains("currencyCode=3:INR")
                .contains("idempotencyKey=-")
                .contains("prevHash=-");
    }

    @Test
    void anAbsentFieldIsDistinctFromAFieldHoldingTheNullMarker() {
        UUID id = Entries.nextId();

        assertThat(EntryHash.canonicalForm(entryWith(id, null, null)))
                .as("if null were written as the same text a value can hold, an absent idempotency key "
                        + "would encode identically to one literally named \"-\", and the chain could not "
                        + "tell a first attempt from a retry of an operation called \"-\"")
                .isNotEqualTo(EntryHash.canonicalForm(entryWith(id, "-", null)));
    }

    /** One entry shape with the three components the hash tests vary: id, key and previous hash. */
    private static JournalEntry entryWith(UUID id, String idempotencyKey, String prevHash) {
        return new JournalEntry(
                id, Entries.GROUP, Entries.INR, Entries.WHEN, EntryKind.OBLIGATION,
                null, idempotencyKey, null, null, false,
                List.of(Entries.debit(Entries.ALICE, 100L, Entries.INR),
                        Entries.credit(Entries.BORIS, 100L, Entries.INR)),
                prevHash);
    }

    private static long netOf(JournalEntry entry) {
        Money net = Money.zero(entry.currency());
        for (Posting posting : entry.postings()) {
            net = net.plus(posting.signedAmount());
        }
        return net.amountMinor();
    }
}
