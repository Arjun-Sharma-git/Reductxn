package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

import java.time.Instant;
import java.util.Objects;

/**
 * An exchange rate as a scaled integer.
 *
 * <p>Principle P12: a rate is never a {@code double} and never a {@code float}.
 * {@code rateScaled / 10^rateScale} is the rate exactly, and every conversion from there is exact
 * integer arithmetic (ADR 0013). The same technique as {@code Money}, for the same reason: a
 * binary floating-point rate multiplied by an exact amount loses precision silently, and the error
 * compounds across every conversion in every cycle.
 *
 * <p>Both the applied rate and the provider's mid rate are recorded. Storing only the applied rate
 * would hide the spread from the group; storing only the mid would make it impossible to tell
 * whether a spread had been applied at all.
 *
 * @param base          the currency being converted from
 * @param quote         the currency being converted to
 * @param rateScaled    numerator of the rate; strictly positive
 * @param rateScale     denominator exponent, 0 to {@value #MAX_SCALE}
 * @param midRateScaled the provider's mid-market numerator at the same scale, for disclosing spread
 * @param spreadBps     spread applied, in basis points; 0 when the rate is all-in
 * @param quotedAt      when the provider quoted it
 * @param expiresAt     when it stops being usable; strictly after {@code quotedAt}
 * @param source        provider identifier, for audit
 */
public record FxRate(
        Currency base,
        Currency quote,
        long rateScaled,
        int rateScale,
        long midRateScaled,
        int spreadBps,
        Instant quotedAt,
        Instant expiresAt,
        String source) {

    /**
     * The largest scale a provider may declare.
     *
     * <p>Not arbitrary. The conversion multiplies by {@code 10^rateScale} worth of denominator, and
     * the exponent is bounded at {@code 2 * MAX_SCALE} so the half-up comparison
     * ({@code 2 * remainder}) stays inside a {@code long}. See {@code docs/specs/fx.md} section 3.2.
     */
    public static final int MAX_SCALE = 8;

    public FxRate {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(quotedAt, "quotedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(source, "source");

        if (base.equals(quote)) {
            throw new InvalidRateException(
                    "base and quote are both " + base.code()
                            + "; a currency has no rate against itself");
        }
        if (rateScaled <= 0L) {
            throw new InvalidRateException("rateScaled must be positive: " + rateScaled);
        }
        if (rateScale < 0 || rateScale > MAX_SCALE) {
            throw new InvalidRateException(
                    "rateScale out of range 0.." + MAX_SCALE + ": " + rateScale);
        }
        if (midRateScaled <= 0L) {
            throw new InvalidRateException("midRateScaled must be positive: " + midRateScaled);
        }
        if (spreadBps < 0) {
            throw new InvalidRateException("spreadBps must not be negative: " + spreadBps);
        }
        if (source.isBlank()) {
            throw new InvalidRateException("source must not be blank");
        }
        if (!expiresAt.isAfter(quotedAt)) {
            throw new InvalidRateException(
                    "expiresAt must be strictly after quotedAt: " + quotedAt + " then " + expiresAt);
        }
    }

    /**
     * A rate with no spread applied: the mid rate is the applied rate.
     *
     * <p>Named for what it asserts rather than defaulted silently, so a reader can see that no spread
     * was applied instead of inferring it from two equal fields.
     */
    public static FxRate ofMid(
            Currency base,
            Currency quote,
            long rateScaled,
            int rateScale,
            Instant quotedAt,
            Instant expiresAt,
            String source) {
        return new FxRate(
                base, quote, rateScaled, rateScale, rateScaled, 0, quotedAt, expiresAt, source);
    }

    /**
     * Whether this rate may be locked at the given instant.
     *
     * <p>Requires the quote to have already happened and not to have expired. A rate quoted in the
     * future is refused as well: it cannot be evidence of a price anyone could have transacted on.
     */
    public boolean isUsableAt(Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return !asOf.isBefore(quotedAt) && asOf.isBefore(expiresAt);
    }

    /** Whether a spread is present, either declared in basis points or visible as a rate difference. */
    public boolean hasSpread() {
        return spreadBps > 0 || rateScaled != midRateScaled;
    }

    /**
     * The pair as {@code BASE/QUOTE}, for logs and audit.
     *
     * <p>Deliberately not a display string. User-facing rendering is the client's job, per locale
     * (RULE F11, ADR 0014), and a rate rendered as "0.55904" is a locale decision, not a domain one.
     */
    public String pair() {
        return base.code() + "/" + quote.code();
    }

    @Override
    public String toString() {
        return pair() + " " + rateScaled + "e-" + rateScale
                + (hasSpread() ? " (mid " + midRateScaled + ", spread " + spreadBps + "bps)" : "")
                + " @" + source;
    }
}
