package io.reductxn.fx.internal;

import io.reductxn.fx.api.AmountTooSmallToConvertException;
import io.reductxn.fx.api.FxConversion;
import io.reductxn.fx.api.FxConverter;
import io.reductxn.fx.api.FxRate;
import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.Objects;

/**
 * Exact conversion using scaled integers (ADR 0013, P12).
 *
 * <p>The full algorithm, and the reasons for the order of the steps, are in {@code docs/specs/fx.md}
 * section 3.2. The two cancellation loops are the parts a future reader is most likely to delete as
 * redundant; they are not redundant, they are what keeps {@code amountMinor * rateScaled} inside a
 * {@code long} for realistic currency pairs.
 *
 * <p>Stateless and side-effect free, so one instance is shared and reused.
 */
public final class ScaledFxConverter implements FxConverter {

    @Override
    public FxConversion convert(Money amount, FxRate rate) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rate, "rate");

        Currency source = amount.currency();
        if (!source.equals(rate.base())) {
            throw new InvalidRateException(source, rate.base());
        }

        long magnitude = amount.amountMinor();
        if (magnitude < 0L) {
            throw new IllegalArgumentException(
                    "conversion takes a non-negative amount; direction belongs to the transfer "
                            + "instruction, not the amount (L2). Got: " + magnitude);
        }

        int denominatorExponent = rate.rateScale();
        long factor = rate.rateScaled();

        // Cancellation 1: strip trailing zeros from the rate against its own scale. Exact, because
        // dividing numerator and denominator by ten leaves the rational value unchanged.
        while (denominatorExponent > 0 && factor % 10L == 0L) {
            factor /= 10L;
            denominatorExponent--;
        }

        long numerator = Math.multiplyExact(magnitude, factor);

        int exponentShift = rate.quote().minorUnitExponent() - source.minorUnitExponent();
        if (exponentShift > 0) {
            numerator = Math.multiplyExact(numerator, PowersOfTen.of(exponentShift));
        } else if (exponentShift < 0) {
            denominatorExponent -= exponentShift;
        }

        // Cancellation 2: the exponent shift or the amount itself may have introduced factors of ten.
        while (denominatorExponent > 0 && numerator % 10L == 0L) {
            numerator /= 10L;
            denominatorExponent--;
        }

        long denominator = PowersOfTen.of(denominatorExponent);
        long quotient = numerator / denominator;
        long remainder = numerator % denominator;

        // Half-up, deliberately the same rule Money.percentageBps uses. One rounding rule in the
        // codebase, not one per module. Safe from overflow: denominator <= 10^16, so 2 * remainder
        // stays well inside a long.
        boolean roundUp = remainder + remainder >= denominator;

        long convertedMinor = roundUp ? quotient + 1L : quotient;
        long residualScaled = roundUp ? remainder - denominator : remainder;

        // X3: a non-zero debt must never silently become zero. Refusing is better than a debt that
        // vanishes, and a zero transfer would be rejected downstream anyway (N3).
        if (convertedMinor == 0L && magnitude != 0L) {
            throw new AmountTooSmallToConvertException(amount, rate);
        }

        return new FxConversion(
                Money.of(convertedMinor, rate.quote()), residualScaled, denominatorExponent);
    }
}
