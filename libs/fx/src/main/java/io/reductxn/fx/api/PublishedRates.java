package io.reductxn.fx.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * One provider's publication: what it quoted, against a single base currency, at one instant.
 *
 * <p>This is the shape a market-data provider actually publishes. The European Central Bank publishes a table
 * of "currency, rate" per working day, every rate being *units of that currency per one euro* - so the table is
 * a set of observations against one base, not a set of pairs. Modelled that way rather than as a list of
 * {@link FxRate} values, because the pairs a deployment needs are a *decision* (which currencies it supports)
 * and the observations are the *fact* (what the provider said). Deriving one from the other is
 * {@link CrossRates}, and the reason it lives in this module rather than in the adapter is that it is rate
 * arithmetic.
 *
 * <p><strong>One scale for the whole table.</strong> A provider quotes some currencies with two fractional
 * digits and some with five, and a ratio between two observations is only exact if both are expressed at the
 * same scale. Normalising the table to its own maximum scale when it is parsed makes that true by construction,
 * and it costs nothing to carry: the scale cancels in a ratio, so the choice cannot change any derived rate
 * (spec section 2 - a rate is {@code rateScaled / 10^rateScale} exactly, and both values feed the same
 * denominator).
 *
 * @param provider      who published it, recorded on every rate derived from it so an auditor can name the source
 * @param baseCode      the currency the table is referenced against, EUR for the ECB's daily rates
 * @param scale         the common scale of every value in {@code unitsPerBase}
 * @param publishedAt   when the provider published it, which is when its rates may first be locked
 * @param unitsPerBase  units of each currency per one unit of {@code baseCode}, as {@code scaled / 10^scale}
 */
public record PublishedRates(
        String provider,
        String baseCode,
        int scale,
        Instant publishedAt,
        Map<String, Long> unitsPerBase) {

    public PublishedRates {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(baseCode, "baseCode");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(unitsPerBase, "unitsPerBase");

        if (provider.isBlank()) {
            throw new InvalidRateException("a publication has to name the provider it came from");
        }
        if (baseCode.isBlank()) {
            throw new InvalidRateException("a publication has to say which currency its rates are against");
        }
        if (scale < 0 || scale > FxRate.MAX_SCALE) {
            throw new InvalidRateException(
                    "the publication's scale is out of range 0.." + FxRate.MAX_SCALE + ": " + scale);
        }
        if (unitsPerBase.isEmpty()) {
            throw new InvalidRateException(
                    "a publication with no rates in it is not a publication: " + provider);
        }
        if (unitsPerBase.containsKey(baseCode)) {
            throw new InvalidRateException(
                    "the publication quotes " + baseCode + " against itself, which is not a rate");
        }
        for (Map.Entry<String, Long> quoted : unitsPerBase.entrySet()) {
            if (quoted.getKey() == null || quoted.getKey().isBlank()) {
                throw new InvalidRateException("a publication quoted a rate for a currency with no code");
            }
            if (quoted.getValue() == null || quoted.getValue() <= 0L) {
                throw new InvalidRateException("a publication quoted " + quoted.getKey() + " at "
                        + quoted.getValue() + "; a rate is strictly positive");
            }
        }

        unitsPerBase = Map.copyOf(unitsPerBase);
    }

    /** The codes this publication quotes, sorted, so a report about it reads the same twice. */
    public Set<String> codes() {
        return new TreeSet<>(unitsPerBase.keySet());
    }

    /** The number of codes quoted, which is what a report counts rather than lists. */
    public int quotedCurrencies() {
        return unitsPerBase.size();
    }

    /**
     * Whether this publication can express a rate for the given code.
     *
     * <p>True for the base itself, at the identity: the table says nothing about the base because every one of
     * its values already is a rate against it, and a caller asking for the base is asking for the unit.
     */
    public boolean quotes(String code) {
        Objects.requireNonNull(code, "code");
        return baseCode.equals(code) || unitsPerBase.containsKey(code);
    }

    /**
     * Units of {@code code} per one unit of {@link #baseCode()}, as {@code scaled / 10^scale}.
     *
     * @throws InvalidRateException when the publication does not quote this code, because a caller that derives
     *                              a rate from a missing observation would otherwise be handed a plausible zero
     */
    public long unitsPerBase(String code) {
        Objects.requireNonNull(code, "code");

        if (baseCode.equals(code)) {
            return powerOfTen(scale);
        }

        Long units = unitsPerBase.get(code);

        if (units == null) {
            throw new InvalidRateException(
                    provider + " published no rate for " + code + " against " + baseCode);
        }

        return units;
    }

    /** {@code 10^exponent}, for the identity rate and for the derivation's numerator. */
    static long powerOfTen(int exponent) {
        long result = 1L;
        for (int i = 0; i < exponent; i++) {
            result *= 10L;
        }
        return result;
    }
}
