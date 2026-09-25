package io.reductxn.fx.api;

import io.reductxn.ledger.api.Money;

/**
 * Converts an amount from the rate's base currency into its quote currency (ADR 0013).
 *
 * <p>A single method on purpose. Conversion is a pure function of an amount and a rate; anything that
 * needs a provider, a clock or a database belongs in the application layer that calls this, not
 * behind this interface.
 *
 * @see FxConverters#exact()
 */
@FunctionalInterface
public interface FxConverter {

    /**
     * Converts {@code amount} at {@code rate}.
     *
     * @param amount the amount to convert, in {@code rate.base()}. Must not be negative: direction
     *               belongs to the transfer instruction, not to the amount (L2)
     * @param rate   the rate to apply
     * @return the booked amount and the fractional residual
     * @throws InvalidRateException              when the amount is not in the rate's base currency
     * @throws AmountTooSmallToConvertException  when a non-zero amount would round to zero
     * @throws ArithmeticException               when the exact product leaves the range of a
     *                                           {@code long}. Thrown rather than wrapped: a wrapped
     *                                           result would be a plausible-looking wrong number
     */
    FxConversion convert(Money amount, FxRate rate);
}
