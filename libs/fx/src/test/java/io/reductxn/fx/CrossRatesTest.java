package io.reductxn.fx;

import io.reductxn.fx.api.CrossRates;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.fx.api.PublishedRates;
import io.reductxn.ledger.api.Currency;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deriving the rates a deployment needs from a publication that quotes one base.
 *
 * <p>The numbers are the European Central Bank's daily reference rates for 25 September 2026, which is the
 * publication this feature was written against: EUR 1.1403 USD, 179.70 JPY, 109.2605 INR. The ECB quotes every
 * currency against the euro, so a rupee-per-yen rate is a ratio of two of those observations - and every
 * expected value below is that ratio computed by hand rather than copied from what the code produced.
 */
class CrossRatesTest {

    private static final String ECB = "ecb";
    private static final Currency EUR = Currency.of("EUR", 2);
    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency JPY = Currency.of("JPY", 0);
    private static final Currency USD = Currency.of("USD", 2);

    /** 16:00 in Brussels on a September day, which is 14:00 UTC - the ECB's publication instant. */
    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-25T14:00:00Z");

    private static final Duration FRESHNESS = Duration.ofDays(4);

    /** The five-decimals scale the ECB's own document uses, because one of its rates has five. */
    private static final int SCALE = 5;

    private static final PublishedRates PUBLICATION = new PublishedRates(
            ECB, "EUR", SCALE, PUBLISHED_AT,
            Map.of("USD", 114_030L, "JPY", 17_970_000L, "INR", 10_926_050L));

    @Test
    void derivesEveryOrderedPairTheDeploymentCanTransact() {
        List<FxRate> rates = CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY, USD), FRESHNESS);

        assertThat(rates)
                .as("three currencies, both directions of each pair, and never a currency against itself")
                .hasSize(6)
                .allSatisfy(rate -> assertThat(rate.base()).isNotEqualTo(rate.quote()));
    }

    @Test
    void derivesAPairTheProviderNeverPublished() {
        // 109.2605 / 179.70 = 0.60801614, to eight digits, half-up.
        FxRate rupeesPerYen = rate(CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY), FRESHNESS), JPY, INR);

        assertThat(rupeesPerYen.rateScaled()).isEqualTo(60_801_614L);
        assertThat(rupeesPerYen.rateScale()).isEqualTo(CrossRates.DERIVED_SCALE);
        assertThat(rupeesPerYen.pair()).isEqualTo("JPY/INR");
    }

    @Test
    void derivesBothDirectionsIndependentlyRatherThanInvertingOne() {
        List<FxRate> rates = CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY), FRESHNESS);

        // 179.70 / 109.2605 = 1.64469319. Not the inverse of the other direction as a stored value: each is
        // rounded from the same pair of observations, so neither inherits the other's rounding.
        assertThat(rate(rates, INR, JPY).rateScaled()).isEqualTo(164_469_319L);
    }

    @Test
    void aRateAgainstThePublicationsOwnBaseIsThePublishedValue() {
        List<FxRate> rates = CrossRates.ratesFor(PUBLICATION, List.of(EUR, INR, USD), FRESHNESS);

        FxRate eurosToRupees = rate(rates, EUR, INR);

        assertThat(eurosToRupees.rateScaled())
                .as("109.2605 published at five digits is 109.26050000 at eight, and nothing is rounded away")
                .isEqualTo(10_926_050_000L);
        assertThat(eurosToRupees.source())
                .as("the provider's own value, so the provider's own name")
                .isEqualTo(ECB);
    }

    @Test
    void aDerivedPairSaysSoInItsSource() {
        List<FxRate> rates = CrossRates.ratesFor(PUBLICATION, List.of(EUR, JPY), FRESHNESS);

        assertThat(rate(rates, JPY, EUR).source())
                .as("an auditor looking for this number in the ECB's archive will not find it")
                .isEqualTo(ECB + "-cross");
    }

    @Test
    void aDerivedRateIsAllInAndExpiresOnTheDeploymentsTerms() {
        FxRate rate = rate(CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY), FRESHNESS), JPY, INR);

        assertThat(rate.midRateScaled())
                .as("the provider published a mid rate and no spread was applied to it")
                .isEqualTo(rate.rateScaled());
        assertThat(rate.hasSpread()).isFalse();
        assertThat(rate.quotedAt())
                .as("usable from the publication, not from midnight of its date")
                .isEqualTo(PUBLISHED_AT);
        assertThat(rate.expiresAt()).isEqualTo(PUBLISHED_AT.plus(FRESHNESS));
        assertThat(rate.isUsableAt(PUBLISHED_AT)).isTrue();
        assertThat(rate.isUsableAt(PUBLISHED_AT.minusSeconds(1)))
                .as("a rate quoted in the future is not evidence of a price anyone could have transacted on")
                .isFalse();
    }

    @Test
    void refusesToDeriveAgainstACurrencyTheProviderDidNotQuote() {
        Currency rub = Currency.of("RUB", 2);

        assertThatThrownBy(() -> CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY, rub), FRESHNESS))
                .as("a caller that silently got no RUB rate would report success while every RUB transfer "
                        + "failed closed later")
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("RUB");
    }

    @Test
    void refusesAFreshnessWindowThatWouldExpireARateBeforeItWasPublished() {
        assertThatThrownBy(() -> CrossRates.ratesFor(PUBLICATION, List.of(INR, JPY), Duration.ZERO))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("freshness");
    }

    @Test
    void refusesADerivedRateOutsideTheRangeARateMayHave() {
        // A publication quoting 0.00000001 of one currency and 0.001 of another against the base. The ratio is
        // 100,000 units each way, which at eight digits is 10^13 - a real number, and outside the 10^12 the
        // spec allows, so it is refused rather than stored and converted with.
        PublishedRates absurd = new PublishedRates("absurd", "XXX", 8, PUBLISHED_AT,
                Map.of("YYY", 1L, "ZZZ", 100_000L));

        assertThatThrownBy(() -> CrossRates.ratesFor(absurd,
                List.of(Currency.of("YYY", 2), Currency.of("ZZZ", 2)), FRESHNESS))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("absurd")
                .hasMessageContaining("docs/specs/fx.md");
    }

    @Test
    void refusesRatherThanWrappingWhenTheProductLeavesALong() {
        // 10^12 published units, multiplied by 10^8 to express the rate at eight digits, is 10^20.
        PublishedRates absurd = new PublishedRates("absurd", "XXX", 8, PUBLISHED_AT,
                Map.of("YYY", 1L, "ZZZ", 1_000_000_000_000L));

        assertThatThrownBy(() -> CrossRates.ratesFor(absurd,
                List.of(Currency.of("YYY", 2), Currency.of("ZZZ", 2)), FRESHNESS))
                .as("a wrapped value is a plausible-looking wrong rate")
                .isInstanceOf(ArithmeticException.class);
    }

    private static FxRate rate(List<FxRate> rates, Currency base, Currency quote) {
        return rates.stream()
                .filter(rate -> rate.base().equals(base) && rate.quote().equals(quote))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + base.code() + "/" + quote.code() + " in " + rates));
    }
}
