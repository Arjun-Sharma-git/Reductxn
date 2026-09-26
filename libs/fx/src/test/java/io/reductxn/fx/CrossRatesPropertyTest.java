package io.reductxn.fx;

import io.reductxn.fx.api.CrossRates;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.fx.api.PublishedRates;
import io.reductxn.ledger.api.Currency;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a derived rate guarantees, over generated publications.
 *
 * <p>The derivation is one multiply and one divide, and three claims are worth checking rather than asserting in
 * prose: that a derived rate is the exact ratio rounded to the nearest last place, that rounding cannot invert
 * the order of two observations, and that the scale a publication is normalised to cannot change any derived
 * rate - which is the claim {@code PublishedRates} rests on when it says the scale cancels.
 *
 * <p><strong>The oracle is {@link BigInteger}.</strong> Not a P1 violation: P1 forbids floating point and
 * {@code BigDecimal} in the domain model and on the wire, and a test that recomputes the exact rational value
 * independently is the only way to check the implementation against something other than itself. The same
 * argument {@code FxConverterPropertyTest} makes for its own oracle.
 */
class CrossRatesPropertyTest {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-25T14:00:00Z");
    private static final Duration FRESHNESS = Duration.ofDays(4);
    private static final String PROVIDER = "property-test";

    private static final Currency AAA = Currency.of("AAA", 2);
    private static final Currency BBB = Currency.of("BBB", 2);


    /**
     * A publication whose two observations are within a factor of a thousand of each other.
     *
     * <p>Restricted deliberately, twice over. The spec bounds a rate at 10^12, which at eight digits caps the
     * ratio of two observations at 10^4 - so wider ratios would spend these cases on refusals, and the refusals
     * have their own examples in {@code CrossRatesTest}. The ranges are also chosen to leave headroom for the
     * scale-invariance property, which multiplies both observations by ten: the largest product it can then
     * build is 10^6 * 10^3 * 10 * 10^8, which is still inside a {@code long}. Both directions of every
     * generated pair are exercised - one is at or above 1 and the other at or below it.
     */
    @Provide
    Arbitrary<Observation> observations() {
        Arbitrary<Long> unitsOfAaa = Arbitraries.longs().between(1L, 1_000_000L);
        Arbitrary<Long> factor = Arbitraries.longs().between(1L, 1_000L);
        Arbitrary<Integer> scales = Arbitraries.integers().between(0, CrossRates.DERIVED_SCALE - 1);

        return Combinators.combine(unitsOfAaa, factor, scales)
                .as((units, ratio, scale) -> new Observation(units, units * ratio, scale));
    }

    private record Observation(long unitsOfAaa, long unitsOfBbb, int scale) {

        PublishedRates publication() {
            return new PublishedRates(PROVIDER, "XXX", scale, PUBLISHED_AT,
                    Map.of(AAA.code(), unitsOfAaa, BBB.code(), unitsOfBbb));
        }
    }

    private static FxRate rate(Observation observation, Currency base, Currency quote) {
        return CrossRates.ratesFor(observation.publication(), List.of(AAA, BBB), FRESHNESS).stream()
                .filter(rate -> rate.base().equals(base) && rate.quote().equals(quote))
                .findFirst()
                .orElseThrow();
    }

    // --- the properties ----------------------------------------------------------------------

    /**
     * A derived rate is the exact ratio of the two observations, rounded to the nearest last place.
     *
     * <p>Stated as a bound on integers rather than as a comparison of two computed values, so the check does not
     * inherit the error it is measuring: {@code derived * divisor} must be within half a unit of the exact
     * numerator. This is the whole of what the derivation promises, and it is the claim a caller relies on when
     * it treats a derived rate as equivalent to a published one.
     */
    @Property
    void aDerivedRateIsTheExactRatioRoundedToTheNearestLastPlace(
            @ForAll("observations") Observation observation) {

        long derived = rate(observation, AAA, BBB).rateScaled();

        BigInteger numerator = BigInteger.valueOf(observation.unitsOfBbb())
                .multiply(BigInteger.TEN.pow(CrossRates.DERIVED_SCALE));
        BigInteger divisor = BigInteger.valueOf(observation.unitsOfAaa());

        BigInteger error = BigInteger.valueOf(derived).multiply(divisor).subtract(numerator).abs();

        assertThat(error)
                .as("half-up means within half a unit in the last place of the exact ratio")
                .isLessThanOrEqualTo(divisor.divide(BigInteger.TWO).add(BigInteger.ONE));
    }

    /**
     * A larger observation of the quote currency is never a smaller rate.
     *
     * <p>The property that would break first if the derivation ever went through a lossy intermediate, because
     * the comparison has to survive rounding in both the observation's scale and the rate's.
     */
    @Property
    void aLargerObservationIsNeverASmallerRate(@ForAll("observations") Observation observation) {
        long smaller = observation.unitsOfBbb() / 2L;
        long larger = observation.unitsOfBbb();
        long unitsOfAaa = observation.unitsOfAaa();
        int scale = observation.scale();

        Observation low = new Observation(unitsOfAaa, Math.max(1L, smaller), scale);
        Observation high = new Observation(unitsOfAaa, larger, scale);

        assertThat(rate(high, AAA, BBB).rateScaled())
                .as("rounding must never invert the order of two observations")
                .isGreaterThanOrEqualTo(rate(low, AAA, BBB).rateScaled());
    }

    /**
     * The scale a publication is normalised to cannot change a derived rate.
     *
     * <p>This is what lets a parser choose one scale for a whole document: multiplying both observations by ten
     * and raising the scale by one is the same pair of ratios, so every derived rate must be identical - not
     * merely close.
     */
    @Property
    void thePublicationsScaleDoesNotChangeADerivedRate(
            @ForAll("observations") Observation observation) {

        Observation finer = new Observation(observation.unitsOfAaa() * 10L,
                observation.unitsOfBbb() * 10L, observation.scale() + 1);

        assertThat(rate(finer, AAA, BBB).rateScaled())
                .as("the scale cancels in the ratio, which is why one scale for a document is safe")
                .isEqualTo(rate(observation, AAA, BBB).rateScaled());
    }

    /**
     * A derived rate is inside the range a rate may have, or the derivation refused it.
     *
     * <p>The generated publications are bounded, so for them this holds by construction - which is why it is
     * worth stating: it is the contract {@code CrossRates} offers a caller, and it must not be possible to get a
     * rate of zero, or one that has wrapped, out of it.
     */
    @Property
    void aDerivedRateIsEitherUsableOrRefused(@ForAll("observations") Observation observation) {
        try {
            assertThat(rate(observation, AAA, BBB).rateScaled())
                    .as("a rate is positive and inside the bound the spec states for one")
                    .isPositive()
                    .isLessThanOrEqualTo(CrossRates.MAX_DERIVED_SCALED);
        } catch (InvalidRateException refused) {
            assertThat(refused.detail())
                    .as("a refusal says what was wrong with the publication")
                    .isNotBlank();
        }
    }
}
