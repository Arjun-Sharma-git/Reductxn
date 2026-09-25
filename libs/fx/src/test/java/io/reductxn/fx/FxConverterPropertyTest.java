package io.reductxn.fx;

import io.reductxn.fx.api.AmountTooSmallToConvertException;
import io.reductxn.fx.api.FxConversion;
import io.reductxn.fx.api.FxConverter;
import io.reductxn.fx.api.FxConverters;
import io.reductxn.fx.api.FxRate;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The FX properties from {@code docs/specs/fx.md} section 8, checked over generated inputs.
 *
 * <p>Only X1-X6 are here. X7 (per-currency zero-sum after a cross-currency cycle) is a property of the
 * ledger and the netting partitioner rather than of this module, and it lands in the phase that wires
 * conversion into postings. It is not quietly skipped; the spec records it as deferred.
 *
 * <p><strong>X2 uses {@link BigInteger} as an oracle.</strong> That is not a P1 violation. P1 forbids
 * floating point and {@code BigDecimal} in the domain model and on the wire. A test that recomputes
 * the exact rational value independently is the only way to check the implementation against
 * something other than itself.
 */
class FxConverterPropertyTest {

    private static final FxConverter FX = FxConverters.exact();

    private static final Instant QUOTED_AT = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-25T12:00:00Z");
    private static final String SOURCE = "property-test";

    /**
     * Currencies that all share exponent 2.
     *
     * <p>Restricted deliberately, so X4's round-trip bound measures the rounding it is meant to
     * measure. An exponent shift adds its own quantisation on top, and the spec states X4 for a
     * same-exponent pair.
     */
    private static final Currency INR = Currency.of("INR", 2);
    private static final Currency USD = Currency.of("USD", 2);
    private static final Currency RUB = Currency.of("RUB", 2);

    private static final List<Pair> PAIRS = List.of(
            new Pair(INR, USD), new Pair(INR, RUB), new Pair(USD, INR),
            new Pair(USD, RUB), new Pair(RUB, INR), new Pair(RUB, USD));

    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L};

    /** Highest rate numerator the generators use, keeping the product well inside a long. */
    private static final long MAX_RATE_SCALED = 10_000L;

    private static final long MAX_AMOUNT_MINOR = 1_000_000_000L;

    private record Pair(Currency base, Currency quote) {
    }

    private record Invertible(FxRate forward, FxRate inverse) {
    }

    private static long pow(long base, int exponent) {
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result *= base;
        }
        return result;
    }

    private static long pow10(int exponent) {
        return POW10[exponent];
    }

    // --- generators ---------------------------------------------------------------------------

    /**
     * Rates at or above 1, at scale 0..4, over same-exponent pairs.
     *
     * <p>The {@code rate >= 1} floor is what X4's bound assumes. Generating smaller rates would make
     * the "within one minor unit" claim false, and a generator that quietly produced them would be
     * checking something weaker than the spec states.
     */
    @Provide
    Arbitrary<FxRate> usableRates() {
        return Arbitraries.of(PAIRS).flatMap(pair ->
                Arbitraries.integers().between(0, 4).flatMap(scale ->
                        Arbitraries.longs().between(POW10[scale], MAX_RATE_SCALED)
                                .map(scaled -> FxRate.ofMid(
                                        pair.base(), pair.quote(), scaled, scale,
                                        QUOTED_AT, EXPIRES_AT, SOURCE))));
    }

    @Provide
    Arbitrary<Long> amounts() {
        return Arbitraries.longs().between(1L, MAX_AMOUNT_MINOR);
    }

    /**
     * A rate and its <em>exact</em> inverse.
     *
     * <p>The numerator is built as {@code 2^a * 5^b}, which is what makes an exact reciprocal
     * representable as a scaled integer. A rounded inverse would make X4 measure the inverse rate's
     * own error rather than the conversion's.
     */
    @Provide
    Arbitrary<Invertible> invertibleRates() {
        return Arbitraries.of(PAIRS).flatMap(pair ->
                Arbitraries.integers().between(0, 3).flatMap(scale ->
                        Arbitraries.integers().between(scale, 5).flatMap(a ->
                                Arbitraries.integers().between(scale, 5).map(b -> {
                                    long numerator = pow(2, a) * pow(5, b);
                                    int maxExponent = Math.max(a, b);
                                    // 1 / (numerator / 10^scale) = 10^scale / numerator, written at
                                    // the smallest scale that keeps it a whole number.
                                    long inverseScaled = pow10(maxExponent) / numerator;
                                    return new Invertible(
                                            FxRate.ofMid(pair.base(), pair.quote(), numerator, scale,
                                                    QUOTED_AT, EXPIRES_AT, SOURCE),
                                            FxRate.ofMid(pair.quote(), pair.base(), inverseScaled,
                                                    maxExponent - scale,
                                                    QUOTED_AT, EXPIRES_AT, SOURCE));
                                }))));
    }

    /** Amounts far beyond anything a single conversion can represent. */
    @Provide
    Arbitrary<Long> amountsBeyondTheRepresentableProduct() {
        return Arbitraries.longs().between(1_000_000_000_000_000L, 1_000_000_000_000_000_000L);
    }

    // --- X1 -----------------------------------------------------------------------------------

    @Property
    void x1_theSameInputsAlwaysProduceTheSameResult(
            @ForAll("usableRates") FxRate rate, @ForAll("amounts") long amountMinor) {
        FxConversion first = FX.convert(Money.of(amountMinor, rate.base()), rate);
        FxConversion second = FX.convert(Money.of(amountMinor, rate.base()), rate);

        assertThat(second).as("X1: conversion is deterministic").isEqualTo(first);
    }

    // --- X2 -----------------------------------------------------------------------------------

    @Property
    void x2_valueIsConservedExactly(
            @ForAll("usableRates") FxRate rate, @ForAll("amounts") long amountMinor) {
        FxConversion result = FX.convert(Money.of(amountMinor, rate.base()), rate);

        int exponentShift = rate.quote().minorUnitExponent() - rate.base().minorUnitExponent();

        // The exact rational value, recomputed independently of the implementation.
        BigInteger numerator = BigInteger.valueOf(amountMinor)
                .multiply(BigInteger.valueOf(rate.rateScaled()));
        BigInteger denominator = BigInteger.TEN.pow(rate.rateScale());
        if (exponentShift > 0) {
            numerator = numerator.multiply(BigInteger.TEN.pow(exponentShift));
        } else if (exponentShift < 0) {
            denominator = denominator.multiply(BigInteger.TEN.pow(-exponentShift));
        }

        BigInteger residualDenominator = BigInteger.TEN.pow(result.residualScale());
        BigInteger booked = BigInteger.valueOf(result.converted().amountMinor())
                .multiply(residualDenominator)
                .add(BigInteger.valueOf(result.residualScaled()));

        // booked / 10^residualScale must equal numerator / denominator, exactly.
        assertThat(booked.multiply(denominator))
                .as("X2: booked amount plus residual equals the exact value")
                .isEqualTo(numerator.multiply(residualDenominator));
    }

    @Property
    void x2_theResidualStaysWithinHalfAMinorUnit(
            @ForAll("usableRates") FxRate rate, @ForAll("amounts") long amountMinor) {
        FxConversion result = FX.convert(Money.of(amountMinor, rate.base()), rate);

        BigInteger denominator = BigInteger.TEN.pow(result.residualScale());

        assertThat(BigInteger.valueOf(result.residualScaled()).abs().multiply(BigInteger.TWO))
                .as("X2: the residual is at most half a minor unit")
                .isLessThanOrEqualTo(denominator);
    }

    // --- X3 -----------------------------------------------------------------------------------

    @Property
    void x3_aNonZeroAmountUnderARepresentableRateNeverBecomesZero(
            @ForAll("usableRates") FxRate rate, @ForAll("amounts") long amountMinor) {
        FxConversion result = FX.convert(Money.of(amountMinor, rate.base()), rate);

        assertThat(result.converted().amountMinor())
                .as("X3: a real amount must never silently vanish")
                .isPositive();
    }

    @Example
    void x3_aConversionThatWouldRoundToZeroIsRefusedRatherThanPerformed() {
        // 1 INR = 0.00001788 USD, so a single paisa is far below one cent.
        FxRate tiny = FxRate.ofMid(INR, USD, 1_788L, 8, QUOTED_AT, EXPIRES_AT, SOURCE);

        assertThatThrownBy(() -> FX.convert(Money.of(1L, INR), tiny))
                .as("X3: refusing is better than making a debt disappear")
                .isInstanceOf(AmountTooSmallToConvertException.class);
    }

    // --- X4 -----------------------------------------------------------------------------------

    @Property
    void x4_roundTripStaysWithinOneMinorUnit(
            @ForAll("invertibleRates") Invertible rates, @ForAll("amounts") long amountMinor) {
        Money original = Money.of(amountMinor, rates.forward().base());

        FxConversion there = FX.convert(original, rates.forward());
        FxConversion back = FX.convert(there.converted(), rates.inverse());

        assertThat(back.converted().currency())
                .as("X4: the round trip returns to the original currency")
                .isEqualTo(original.currency());
        assertThat(Math.abs(back.converted().amountMinor() - amountMinor))
                .as("X4: A -> B -> A is within ceil(0.5 + 0.5/r), which is 1 minor unit for r >= 1")
                .isLessThanOrEqualTo(1L);
    }

    // --- X5 -----------------------------------------------------------------------------------

    @Property
    void x5_aLargerAmountNeverConvertsToLess(
            @ForAll("usableRates") FxRate rate,
            @ForAll("amounts") long first,
            @ForAll("amounts") long second) {
        long smaller = Math.min(first, second);
        long larger = Math.max(first, second);

        FxConversion fromSmaller = FX.convert(Money.of(smaller, rate.base()), rate);
        FxConversion fromLarger = FX.convert(Money.of(larger, rate.base()), rate);

        assertThat(fromLarger.converted().amountMinor())
                .as("X5: rounding must never invert the order")
                .isGreaterThanOrEqualTo(fromSmaller.converted().amountMinor());
    }

    // --- X6 -----------------------------------------------------------------------------------

    @Example
    void x6_atTheLargestRepresentableProductItStillConverts() {
        // rateScale 0, so no cancellation applies and the rate is 10,000 in full. The ceiling is
        // 9 * 10^14 minor units: 10^15 * 10^4 is 10^19 and leaves a long.
        FxRate rate = FxRate.ofMid(INR, USD, 10_000L, 0, QUOTED_AT, EXPIRES_AT, SOURCE);

        FxConversion result = FX.convert(Money.of(900_000_000_000_000L, INR), rate);

        assertThat(result.converted().amountMinor()).isEqualTo(9_000_000_000_000_000_000L);
    }

    @Property
    void x6_anAmountBeyondTheRepresentableProductThrowsRatherThanWrapping(
            @ForAll("amountsBeyondTheRepresentableProduct") long amountMinor) {
        FxRate rate = FxRate.ofMid(INR, USD, 10_000L, 0, QUOTED_AT, EXPIRES_AT, SOURCE);

        assertThatThrownBy(() -> FX.convert(Money.of(amountMinor, INR), rate))
                .as("X6: a wrapped value would be a plausible-looking wrong amount")
                .isInstanceOf(ArithmeticException.class);
    }
}
