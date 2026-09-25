package io.reductxn.ledger;

import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Invariant L2, at the unit level: an amount is a magnitude and the direction carries the sign.
 *
 * <p>The property form of this invariant is in {@code JournalEntryPropertyTest}. What is here are the
 * specific cases worth naming - in particular the zero posting, which is refused because it would let an
 * unbalanced entry look balanced.
 */
class PostingTest {

    @Test
    void directionCarriesTheSign() {
        Posting debit = new Posting(Entries.ALICE, Direction.DEBIT, Money.of(500L, Entries.INR));
        Posting credit = new Posting(Entries.ALICE, Direction.CREDIT, Money.of(500L, Entries.INR));

        assertThat(debit.signedAmount().amountMinor())
                .as("L2: a debit contributes positively, so the stored amount stays a magnitude")
                .isEqualTo(500L);
        assertThat(credit.signedAmount().amountMinor())
                .as("L2: a credit of the same magnitude contributes the opposite sign")
                .isEqualTo(-500L);
    }

    @Test
    void zeroAndNegativeAmountsAreRefused() {
        assertThatThrownBy(() -> new Posting(Entries.ALICE, Direction.DEBIT, Money.of(0L, Entries.INR)))
                .as("L2: a zero posting would balance for free, which is how a missing posting hides "
                        + "inside an entry that still appears to balance")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new Posting(Entries.ALICE, Direction.CREDIT, Money.of(-1L, Entries.INR)))
                .as("L2: a negative amount would put the sign in two places at once, so which side a "
                        + "posting is on would depend on reading the amount and the direction together")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyComponentIsRequired() {
        assertThatThrownBy(() -> new Posting(null, Direction.DEBIT, Money.of(1L, Entries.INR)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Posting(Entries.ALICE, null, Money.of(1L, Entries.INR)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Posting(Entries.ALICE, Direction.DEBIT, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void currencyComesFromTheAmount() {
        Posting posting = new Posting(Entries.ALICE, Direction.DEBIT, Money.of(7L, Entries.TND));

        assertThat(posting.currency())
                .as("the posting's currency is its money's currency, so they cannot disagree")
                .isEqualTo(Entries.TND);
    }
}
