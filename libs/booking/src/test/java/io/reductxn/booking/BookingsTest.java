package io.reductxn.booking;

import io.reductxn.booking.api.Booking;
import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.ChainHead;
import io.reductxn.booking.api.Commitment;
import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Balances;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Direction;
import io.reductxn.ledger.api.EntryKind;
import io.reductxn.ledger.api.FxLinkage;
import io.reductxn.ledger.api.FxRole;
import io.reductxn.ledger.api.JournalEntry;
import io.reductxn.ledger.api.Money;
import io.reductxn.ledger.api.Posting;
import io.reductxn.planning.api.CrossCurrencyObligation;
import io.reductxn.planning.api.FxClearing;
import io.reductxn.planning.api.SingleCurrencyObligation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a commitment books.
 *
 * <p>The invariants are asserted with the ledger's own checkers wherever possible - {@link FxLinkage} for L9
 * and {@link Balances} for the clearing position - rather than by re-implementing them here. A test that
 * recomputed pairing itself would agree with itself and prove nothing about the job that runs in an
 * environment; calling the verifier means this test and that job cannot disagree.
 */
class BookingsTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID OBLIGATION = new UUID(0L, 2L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);

    private static final Instant MADE_AT = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant LOCKED_AT = Instant.parse("2026-09-20T09:59:00Z");
    private static final Instant QUOTED_AT = Instant.parse("2026-09-20T09:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-20T11:00:00Z");

    /** 64 hex characters, as a hash is. Two different values, so a wrong chain lookup would be visible. */
    private static final String JPY_HEAD = "b".repeat(64);
    private static final String INR_HEAD = "c".repeat(64);

    /**
     * A stub chain head that answers per currency and refuses anything else.
     *
     * <p>Refusing rather than returning null for an unexpected currency is what makes this stub an assertion
     * about which chains a booking appends to. A stub that answered null for everything would accept a
     * booking that chained an entry into the wrong currency, and both entries' hashes would look fine.
     */
    private static final ChainHead HEADS = currency -> switch (currency.code()) {
        case "JPY" -> JPY_HEAD;
        case "INR" -> INR_HEAD;
        default -> throw new IllegalArgumentException(
                "this test knows two chains, and booking asked for " + currency.code());
    };

    @Test
    void aSingleCurrencyCommitmentBooksOneEntryFromDebtorToCreditor() {
        JournalEntry entry = Bookings.singleCurrency(
                HEADS, commitment(), new SingleCurrencyObligation(ALICE, BORIS, Money.of(10_000L, INR)));

        assertThat(entry.kind()).isEqualTo(EntryKind.OBLIGATION);
        assertThat(entry.currency()).isEqualTo(INR);
        assertThat(entry.prevHash()).isEqualTo(INR_HEAD);
        assertThat(entry.isFxLinked())
                .as("nothing was converted, so there is no partner entry for a verifier to find")
                .isFalse();
        assertThat(entry.postings()).containsExactly(
                new Posting(ALICE, Direction.CREDIT, Money.of(10_000L, INR)),
                new Posting(BORIS, Direction.DEBIT, Money.of(10_000L, INR)));

        // L1 holds by construction - JournalEntry cannot be built unbalanced - so what these two add is that
        // the direction is the one intended. A debit is positive, so the creditor's position is positive
        // because they hold a claim, and the debtor's is negative because they hold the obligation.
        assertThat(Balances.netDebitOf(BORIS, INR, entry.postings()))
                .as("the creditor holds the claim, so they are debited")
                .isEqualTo(Money.of(10_000L, INR));
        assertThat(Balances.netDebitOf(ALICE, INR, entry.postings()))
                .as("the debtor owes, which reads as a negative net debit")
                .isEqualTo(Money.of(-10_000L, INR));
    }

    @Test
    void aCrossCurrencyCommitmentBooksTwoLinkedEntriesThroughTheClearingAccount() {
        Booking booking = Bookings.crossCurrency(
                HEADS,
                commitment(),
                new CrossCurrencyObligation(ALICE, BORIS, jpyToInr(50_000L)),
                FxClearing.ACCOUNT_ID);

        assertThat(booking.base().currency()).isEqualTo(JPY);
        assertThat(booking.quote().currency()).isEqualTo(INR);
        assertThat(booking.base().fxRole()).isEqualTo(FxRole.BASE);
        assertThat(booking.quote().fxRole()).isEqualTo(FxRole.QUOTE);

        assertThat(booking.base().prevHash())
                .as("the base leg appends to the debt currency's chain, not the settlement currency's")
                .isEqualTo(JPY_HEAD);
        assertThat(booking.quote().prevHash()).isEqualTo(INR_HEAD);

        assertThat(FxLinkage.unpairedLinkIds(booking.entries()))
                .as("L9: the pair this booking produced is paired, checked by the same verifier that will "
                        + "run against the stored entries. Using the checker rather than comparing link ids "
                        + "means the test cannot agree with a broken verifier")
                .isEmpty();

        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, JPY, booking.base().postings()))
                .as("L7: the clearing account holds exactly what the debtor paid, in the debt currency")
                .isEqualTo(Money.of(50_000L, JPY));
        assertThat(Balances.netDebitOf(FxClearing.ACCOUNT_ID, INR, booking.quote().postings()))
                .as("and owes exactly what the creditor is to receive, in the settlement currency. The two "
                        + "figures are not equal and are not comparable - they are different currencies - "
                        + "which is why value conservation across the pair is not something the ledger can "
                        + "check, and why the residual is reported rather than reconciled")
                // Minor units. 50,000 JPY at 0.55904 is 27,952.00 INR, which is 2,795,200 paise - and the
                // first draft of this line said 27,952 because that is the figure the ADR quotes. Two
                // currencies in one test with different exponents is exactly where a major-unit figure
                // looks plausible in a minor-unit field, which is the confusion P1 exists to prevent.
                .isEqualTo(Money.of(-2_795_200L, INR));

        assertThat(booking.residual().residualIsZero())
                .as("ADR 0013's worked example converts exactly, so the clearing account has nothing to "
                        + "absorb")
                .isTrue();
    }

    @Test
    void bookingTheSameCommitmentTwiceProducesTheSameEntries() {
        SingleCurrencyObligation obligation =
                new SingleCurrencyObligation(ALICE, BORIS, Money.of(10_000L, INR));

        JournalEntry first = Bookings.singleCurrency(HEADS, commitment(), obligation);
        JournalEntry second = Bookings.singleCurrency(HEADS, commitment(), obligation);

        assertThat(second.id())
                .as("a re-booking has to collide with the first, because the ledger cannot take an entry "
                        + "back (L3). Deriving the id from the commitment is what makes that a guarantee "
                        + "rather than a hope")
                .isEqualTo(first.id());
        assertThat(second.hash()).isEqualTo(first.hash());
    }

    @Test
    void aZeroAmountIsRefusedRatherThanBooked() {
        assertThatThrownBy(() -> Bookings.singleCurrency(
                HEADS, commitment(), new SingleCurrencyObligation(ALICE, BORIS, Money.of(0L, INR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount of zero");
    }

    @Test
    void passingAMemberWhereTheClearingAccountBelongsIsRefusedRatherThanPosted() {
        // The realistic form of this mistake is wiring the wrong identifier. It has to be refused, because
        // it would not look wrong afterwards: the quote leg would post BORIS against BORIS, which still sums
        // to zero, so the entry would balance perfectly and move nothing.
        assertThatThrownBy(() -> Bookings.crossCurrency(
                HEADS,
                commitment(),
                new CrossCurrencyObligation(ALICE, BORIS, jpyToInr(50_000L)),
                BORIS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot also be one of its parties");
    }

    private static Commitment commitment() {
        return new Commitment(OBLIGATION, GROUP, MADE_AT, "idem-1", null);
    }

    /** ADR 0013's worked example: 50,000 JPY at 0.55904 is exactly 27,952.00 INR. */
    private static FxQuote jpyToInr(long owedMinor) {
        FxRate rate = FxRate.ofMid(JPY, INR, 55_904L, 5, QUOTED_AT, EXPIRES_AT, "test");
        return FxQuote.lock(new UUID(0L, 99L), Money.of(owedMinor, JPY), rate, LOCKED_AT, false);
    }
}
