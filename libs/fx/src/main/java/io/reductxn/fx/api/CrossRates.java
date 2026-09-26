package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Rates between two currencies, derived from a publication made against one.
 *
 * <p>A provider publishes observations, not pairs: the ECB quotes every currency against the euro, so
 * {@code USD} arriving as "1.1403" is one fact and the rate a group needs - yen into rupees, dollars into yen -
 * is arithmetic on two of them. This class is that arithmetic, and it is here rather than in the adapter that
 * reads the document because {@code libs/fx} is the only module allowed to do anything with a rate
 * (RULE B19) and because the derivation is exactly the kind of thing that must not be written twice.
 *
 * <p><strong>One division, and the scale cancels in it.</strong> If one unit of the base is {@code b} units of
 * a currency and {@code q} units of another, then one unit of the first is {@code q / b} units of the second.
 * Both are published at the same scale (see {@link PublishedRates}), so the scale appears in neither the
 * numerator nor the denominator and the result is the ratio of two integers - one {@code long} multiply and one
 * divide. That is the whole derivation, and it is also why the base currency can be derived against as if it
 * were published: its value is the unit.
 *
 * <p><strong>Rounding is half-up at {@value #DERIVED_SCALE} fractional digits, and it is bounded.</strong> A
 * rate is a rational number and the column is an integer, so a derivation has to round somewhere - half-up at
 * eight digits, which is inside the scale a rate may declare (spec section 2) and small enough that the
 * conversion's own residual is measured in minor units rather than in rounding noise. The error a derived rate
 * carries is therefore at most {@code 0.5e-8} relative, and the two directions of a pair are rounded
 * independently, so a round trip is not exactly the identity - it is within that bound, which is a property test
 * rather than a comment ({@code CrossRatesPropertyTest}).
 *
 * <p>Bounds are the spec's, checked rather than assumed. A derived rate is refused when it falls outside the
 * range a rate may have (spec section 2) or when the multiply would leave a {@code long}: a provider whose
 * numbers would produce such a rate is a data incident, and the alternative - storing it and converting with it
 * - is a wrong amount that looks plausible.
 */
public final class CrossRates {

    /**
     * The scale every derived rate is expressed at.
     *
     * <p>Eight, which is {@link FxRate#MAX_SCALE}: a derivation cannot carry more precision than a rate may
     * declare, and a smaller number would throw away precision the provider published - the ECB quotes five
     * fractional digits, and a cross between two of its pairs is the case where that matters.
     */
    public static final int DERIVED_SCALE = FxRate.MAX_SCALE;

    /**
     * The largest numerator a rate may have, from {@code docs/specs/fx.md} section 2.
     *
     * <p>Stated there as a bound on a provider's rate, and applied here to the rate this class invents, because
     * a derivation is the one place reductxn produces a number the market never published.
     */
    public static final long MAX_DERIVED_SCALED = 1_000_000_000_000L;

    private CrossRates() {
    }

    /**
     * Every ordered pair among the given currencies, as rates, from one publication.
     *
     * <p>Both directions of each pair are returned, because a settlement in either direction is a legitimate
     * question and a provider's observation of one direction does not answer the other - inverting it here would
     * be a second derivation with its own rounding, which every reader would then have to know about.
     *
     * <p>Each rate expires {@code freshness} after the publication. That is the deployment's answer to "how old
     * may a published rate be and still be locked", not this module's: a provider that publishes daily is usable
     * over a weekend, and one that streams is not (ADR 0013, point 6).
     *
     * <p>The pair a currency has with itself is skipped - {@link FxRate} refuses it, correctly, and a caller
     * asking for it is asking for the unit.
     *
     * @param published  the provider's observations
     * @param currencies the currencies this deployment can transact, all of which the publication must quote
     * @param freshness  how long a derived rate stays usable after the publication
     * @throws InvalidRateException when a currency is not quoted, or a derived rate falls outside the bounds
     */
    public static List<FxRate> ratesFor(
            PublishedRates published, Collection<Currency> currencies, Duration freshness) {

        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(currencies, "currencies");
        Objects.requireNonNull(freshness, "freshness");

        if (freshness.isZero() || freshness.isNegative()) {
            throw new InvalidRateException("the freshness window must be positive: " + freshness);
        }

        Instant expiresAt = published.publishedAt().plus(freshness);
        List<FxRate> rates = new ArrayList<>();

        for (Currency base : currencies) {
            for (Currency quote : currencies) {
                if (base.equals(quote)) {
                    continue;
                }

                rates.add(rate(published, base, quote, expiresAt));
            }
        }

        return List.copyOf(rates);
    }

    /**
     * One pair, from the provider's own observations of each side.
     *
     * <p>The source distinguishes a derived rate from a published one, which an auditor needs: the ECB published
     * no rupee-per-yen rate, so a reader who finds one under its name and goes looking in its archive will not
     * find that number. A pair quoted against the publication's own base is the provider's value re-expressed at
     * our scale and carries the provider's name alone.
     */
    private static FxRate rate(PublishedRates published, Currency base, Currency quote, Instant expiresAt) {
        long perBase = published.unitsPerBase(base.code());
        long perQuote = published.unitsPerBase(quote.code());

        // Half-up on a positive ratio: adding half the divisor before dividing rounds a half away from zero,
        // which is the direction the conversion rounds in, so the two cannot disagree about a boundary.
        long numerator = Math.multiplyExact(perQuote, PublishedRates.powerOfTen(DERIVED_SCALE));
        long scaled = (numerator + perBase / 2L) / perBase;

        if (scaled <= 0L || scaled > MAX_DERIVED_SCALED) {
            throw new InvalidRateException("the rate " + base.code() + "/" + quote.code() + " derived from "
                    + published.provider() + " is outside the 1.." + MAX_DERIVED_SCALED + " a rate may be: "
                    + scaled + "e-" + DERIVED_SCALE + " (docs/specs/fx.md section 2)");
        }

        String source = base.code().equals(published.baseCode())
                ? published.provider()
                : published.provider() + "-cross";

        return FxRate.ofMid(base, quote, scaled, DERIVED_SCALE,
                published.publishedAt(), expiresAt, source);
    }
}
