package io.reductxn.ledger.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 in practice: money arithmetic must be exact, must reject overflow rather than wrap, and must
 * refuse to mix currencies.
 */
class MoneyTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);

    @Test
    void additionIsExactBeyondTheRangeOfApproximateIntegerTypes() {
        // 9,007,199,254,740,991 is the largest integer an IEEE-754 value represents exactly.
        // Exceeding it is where a representation-based money type silently loses a unit.
        Money large = Money.of(9_007_199_254_740_991L, INR);
        assertThat(large.plus(Money.of(1L, INR)).amountMinor()).isEqualTo(9_007_199_254_740_992L);
    }

    @Test
    void additionThrowsOnOverflowInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, INR).plus(Money.of(1L, INR)))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void mixingCurrenciesIsRejected() {
        assertThatThrownBy(() -> Money.of(100L, INR).plus(Money.of(100L, JPY)))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("INR")
                .hasMessageContaining("JPY");
    }

    @Test
    void percentageRoundsHalfUpToTheNearestMinorUnit() {
        assertThat(Money.of(101L, INR).percentageBps(250L).amountMinor()).isEqualTo(3L); // 2.525 -> 3
        assertThat(Money.of(100L, INR).percentageBps(250L).amountMinor()).isEqualTo(3L); // 2.500 -> 3
        assertThat(Money.of(99L, INR).percentageBps(250L).amountMinor()).isEqualTo(2L);  // 2.475 -> 2
    }

    @Test
    void percentageRejectsANegativeBase() {
        assertThatThrownBy(() -> Money.of(-1L, INR).percentageBps(250L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formattingUsesTheCurrencyExponentWithoutDividing() {
        assertThat(Money.of(400_000L, INR).format()).isEqualTo("4000.00");
        assertThat(Money.of(-1_234L, INR).format()).isEqualTo("-12.34");
        assertThat(Money.of(5L, INR).format()).isEqualTo("0.05");
        assertThat(Money.of(500L, JPY).format()).isEqualTo("500");
        assertThat(Money.zero(INR).format()).isEqualTo("0.00");
    }

    @Test
    void negationAndAbsoluteValueAreExact() {
        assertThat(Money.of(-4_000L, INR).negate().amountMinor()).isEqualTo(4_000L);
        assertThat(Money.of(-4_000L, INR).absolute().amountMinor()).isEqualTo(4_000L);
        assertThat(Money.of(4_000L, INR).absolute().amountMinor()).isEqualTo(4_000L);
    }
}
