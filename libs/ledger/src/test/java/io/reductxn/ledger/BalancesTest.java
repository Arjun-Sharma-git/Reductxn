package io.reductxn.ledger;

import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Posting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The projection itself, and the measurement that L8 needs.
 *
 * <p>None of the assertions here carry an id, deliberately. L5's negative half - that no balance is
 * stored anywhere - is a property of a schema that does not exist yet, and L8's decision half needs the
 * run that does not exist yet, so both stay deferred in {@code PropertyCoverageTest}. Labelling them
 * from a test that cannot make the claim would be the exact overstatement this suite is built to avoid.
 * What is asserted is what genuinely holds: the figure is derived from postings, order cannot change it,
 * and a non-zero clearing position is reported rather than absorbed.
 */
class BalancesTest {

    @Test
    void aPositionIsProjectedFromThePostings() {
        JournalEntry entry = Entries.transfer(Entries.ALICE, Entries.BORIS, 750L, Entries.INR, null);

        assertThat(Balances.netDebitOf(Entries.ALICE, Entries.INR, entry.postings()).amountMinor())
                .as("the payer's net debit is what it paid, computed from the postings rather than read "
                        + "from anywhere")
                .isEqualTo(750L);
        assertThat(Balances.netDebitOf(Entries.BORIS, Entries.INR, entry.postings()).amountMinor())
                .as("and the payee's is the negative of it, because the entry sums to zero")
                .isEqualTo(-750L);
    }

    @Test
    void anUnusedAccountHasAZeroPosition() {
        JournalEntry entry = Entries.transfer(Entries.ALICE, Entries.BORIS, 750L, Entries.INR, null);

        assertThat(Balances.netDebitOf(Entries.CLEARING, Entries.INR, entry.postings()).amountMinor())
                .as("an account that was never posted to has a position of zero rather than an absent "
                        + "one - absence would force every caller to handle a case that has an answer")
                .isZero();
    }

    @Test
    void aMixedCurrencyCollectionIsRefused() {
        JournalEntry inr = Entries.transfer(Entries.ALICE, Entries.BORIS, 100L, Entries.INR, null);
        JournalEntry jpy = Entries.transfer(Entries.ALICE, Entries.BORIS, 100L, Entries.JPY, null);

        List<Posting> mixed = new ArrayList<>(inr.postings());
        mixed.addAll(jpy.postings());

        assertThatThrownBy(() -> Balances.netDebitOf(Entries.ALICE, Entries.INR, mixed))
                .as("summing across currencies with one currency's exponent would produce a figure that "
                        + "means nothing while looking like a number")
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void thePositionDoesNotDependOnTheOrderOfPostings() {
        JournalEntry entry = Entries.transfer(Entries.ALICE, Entries.BORIS, 400L, Entries.INR, null);
        List<Posting> reversed = new ArrayList<>(entry.postings());
        Collections.reverse(reversed);

        assertThat(Balances.netDebitOf(Entries.ALICE, Entries.INR, reversed))
                .as("a projection is a sum, so it cannot depend on the order it is summed in - the hash "
                        + "covers order, the balance does not")
                .isEqualTo(Balances.netDebitOf(Entries.ALICE, Entries.INR, entry.postings()));
    }

    @Test
    void postingsAreSelectedByTheEntrysCurrency() {
        JournalEntry inr = Entries.transfer(Entries.ALICE, Entries.BORIS, 100L, Entries.INR, null);
        JournalEntry jpy = Entries.transfer(Entries.ALICE, Entries.BORIS, 100L, Entries.JPY, null);

        assertThat(Balances.postingsIn(List.of(inr, jpy), Entries.JPY))
                .as("L6 guarantees a posting is in its entry's currency, so filtering entries by "
                        + "currency is exactly filtering their postings")
                .hasSize(2)
                .allSatisfy(posting -> assertThat(posting.currency()).isEqualTo(Entries.JPY));
    }

    @Test
    void aNonZeroClearingPositionIsReportedRatherThanAbsorbed() {
        // ADR 0012's worked example. The two legs do not cancel: clearing ends up holding JPY and short
        // INR, which is the group's genuine external FX requirement (principle P2). A design that netted
        // them against each other would be hiding a real exposure behind an internal account.
        UUID link = Entries.nextLinkId();
        JournalEntry jpyLeg = Entries.conversionSide(
                Entries.GROUP, link, FxRole.BASE, Entries.ALICE, 50_000L, Entries.JPY);
        JournalEntry inrLeg = Entries.conversionSide(
                Entries.GROUP, link, FxRole.QUOTE, Entries.BORIS, 27_952L, Entries.INR);

        assertThat(Balances.netDebitOf(
                Entries.CLEARING, Entries.JPY, Balances.postingsIn(List.of(jpyLeg), Entries.JPY)).amountMinor())
                .as("clearing holds the JPY that Alice gave up")
                .isEqualTo(50_000L);
        assertThat(Balances.netDebitOf(
                Entries.CLEARING, Entries.INR, Balances.postingsIn(List.of(inrLeg), Entries.INR)).amountMinor())
                .as("and is short the INR that Boris received, in a different currency - which is why the "
                        + "position is measured per currency rather than as one number")
                .isEqualTo(-27_952L);
    }
}
