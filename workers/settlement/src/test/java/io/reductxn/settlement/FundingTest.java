package io.reductxn.settlement;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.Funding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * RULE S4's arithmetic, without a database and without Spring.
 *
 * <p>What the funding gate asks is one question per currency - is what arrived at least what the plan needs - and
 * the tests are grouped by the ways that question is easy to get wrong: summing across currencies, letting a
 * surplus in one currency cover a shortfall in another, or losing a funding that nothing was waiting for.
 */
class FundingTest {

    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);

    @Test
    void partialFundingsSumToCoverTheRequirement() {
        Funding funding = Funding.of(List.of(Money.of(224L, JPY)),
                List.of(Money.of(150L, JPY), Money.of(74L, JPY)));

        assertThat(funding.satisfied())
                .as("currency arrives in pieces - half today, half next week - and two arrivals that together "
                        + "cover the requirement have to read as covered")
                .isTrue();
        assertThat(funding.outstanding()).isEmpty();
        assertThat(funding.receivedIn(JPY).amountMinor()).isEqualTo(224L);
    }

    @Test
    void eachCurrencyIsCoveredOnItsOwn() {
        Funding funding = Funding.of(List.of(Money.of(12_500L, INR), Money.of(224L, JPY)),
                List.of(Money.of(12_500L, INR)));

        assertThat(funding.satisfied()).isFalse();
        assertThat(funding.outstanding())
                .as("the shortfall is reported in the currency it is short in, with no total anywhere: 224 yen "
                        + "and 12,500 rupees cannot be added (ADR 0012 point 4)")
                .extracting(Money::currency, Money::amountMinor)
                .containsExactly(tuple(JPY, 224L));
    }

    @Test
    void overFundingCoversRatherThanCarries() {
        Funding funding = Funding.of(List.of(Money.of(224L, JPY)), List.of(Money.of(500L, JPY)));

        assertThat(funding.satisfied()).isTrue();
        assertThat(funding.outstanding()).isEmpty();
        assertThat(funding.receivedIn(JPY).amountMinor())
                .as("the surplus is reported as received, because it did arrive, and it is not carried into any "
                        + "other currency - a group that over-funded has over-funded in one currency")
                .isEqualTo(500L);
    }

    @Test
    void anOrdinaryCycleNeedsNothing() {
        Funding funding = Funding.none();

        assertThat(funding.satisfied()).isTrue();
        assertThat(funding.requiredCurrencies()).isEmpty();
        assertThat(funding.outstanding()).isEmpty();
    }

    @Test
    void aFundingInACurrencyTheRunDoesNotNeedIsVisibleRatherThanDropped() {
        Funding funding = Funding.of(List.of(Money.of(100L, INR)), List.of(Money.of(50L, JPY)));

        assertThat(funding.received())
                .as("a funding nothing was waiting for is reported after the requirements, so it can be seen - "
                        + "dropping it would hide money that a group believes it moved in")
                .extracting(Money::currency, Money::amountMinor)
                .containsExactly(tuple(INR, 0L), tuple(JPY, 50L));
        assertThat(funding.outstanding()).extracting(Money::amountMinor).containsExactly(100L);
        assertThat(funding.satisfied())
                .as("and it does not cover anything: the shortfall is still the shortfall")
                .isFalse();
    }

    @Test
    void theRequirementInOneCurrencyIsAskedWithoutKnowingWhichCurrenciesExist() {
        Funding funding = Funding.of(List.of(Money.of(224L, JPY)), List.of(Money.of(24L, JPY)));

        assertThat(funding.requirementIn(JPY).amountMinor()).isEqualTo(224L);
        assertThat(funding.outstandingIn(JPY).amountMinor()).isEqualTo(200L);
        assertThat(funding.requirementIn(INR))
                .as("a currency the run does not need is zero, not absent and not an error: the caller asking "
                        + "is the funding path, and this is how it reports 'nothing outstanding in rupees'")
                .isEqualTo(Money.zero(INR));
        assertThat(funding.outstandingIn(INR)).isEqualTo(Money.zero(INR));
    }
}
