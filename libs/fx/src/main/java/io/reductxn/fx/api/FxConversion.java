package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.Objects;

/**
 * The result of a conversion: the amount booked, plus exactly what rounding discarded.
 *
 * <p>The residual is **fractional** and stays fractional. It is carried the same way a rate is, as
 * {@code residualScaled / 10^residualScale}, because a whole minor unit cannot represent values like
 * {@code -0.096} and rounding it to an integer would lose or invent value on every conversion
 * (ADR 0013, point 3).
 *
 * <p>The residual is always in {@code [-0.5, 0.5)} minor units of the target currency. That band is
 * asserted by property X2 in the tests rather than enforced here, so this record does not need to
 * depend on the internal power-of-ten table to validate its own fields.
 *
 * <p>What to do with the residual is the ledger's decision, not this module's. `libs/fx` computes it
 * and hands it over; the `fx_clearing` account is not a concept this module knows about.
 *
 * @param converted      the booked amount, in the rate's quote currency
 * @param residualScaled numerator of the residual; negative when rounding rounded up
 * @param residualScale  denominator exponent of the residual
 */
public record FxConversion(Money converted, long residualScaled, int residualScale) {

    /**
     * The largest residual scale that can arise.
     *
     * <p>Rate scale (at most {@code FxRate.MAX_SCALE}) plus an exponent shift of at most the same
     * magnitude, because exponents are bounded at 8 by {@code Currency}.
     */
    public static final int MAX_RESIDUAL_SCALE = 2 * FxRate.MAX_SCALE;

    public FxConversion {
        Objects.requireNonNull(converted, "converted");
        if (residualScale < 0 || residualScale > MAX_RESIDUAL_SCALE) {
            throw new IllegalArgumentException(
                    "residualScale out of range 0.." + MAX_RESIDUAL_SCALE + ": " + residualScale);
        }
    }

    /** Whether rounding was exact, so nothing at all needs booking to a clearing account. */
    public boolean residualIsZero() {
        return residualScaled == 0L;
    }

    /** The currency actually received, which is the rate's quote currency. */
    public Currency targetCurrency() {
        return converted.currency();
    }

    /**
     * The residual rendered as a decimal fraction, for logs.
     *
     * <p>Not for user display: rendering a number for a person is a locale decision and belongs to
     * the client (RULE F11, ADR 0014).
     */
    public String residualAsDecimalString() {
        if (residualScale == 0) {
            return Long.toString(residualScaled);
        }
        boolean negative = residualScaled < 0L;
        String digits = Long.toString(Math.abs(residualScaled));
        String padded = "0".repeat(Math.max(0, residualScale + 1 - digits.length())) + digits;
        int split = padded.length() - residualScale;
        String rendered = padded.substring(0, split) + "." + padded.substring(split);
        return negative ? "-" + rendered : rendered;
    }

    @Override
    public String toString() {
        return converted.toString()
                + (residualIsZero() ? "" : " (residual " + residualAsDecimalString() + ")");
    }
}
