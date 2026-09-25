package io.reductxn.fx.api;

import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable, locked conversion of one amount.
 *
 * <p>Immutability is the point, not incidental. This is the settlement rate: it is what the creditor
 * was promised, so it is never restated. Reporting may value things at today's rate, but the booked
 * conversion is history and stays as written (ADR 0013, point 5).
 *
 * <p>Prefer {@link #lock} over the canonical constructor. It refuses a rate that was not usable at the
 * moment of locking, which is the check that stops an expired quote from being promised to someone.
 *
 * @param id              stable identity, supplied by the caller so this module stays deterministic
 * @param rate            the rate that was applied, kept whole so the spread remains visible
 * @param baseAmount      what the debtor owes, in {@code rate.base()}
 * @param convertedAmount what the creditor receives, in {@code rate.quote()}
 * @param residualScaled  numerator of the fractional rounding residual
 * @param residualScale   denominator exponent of the residual
 * @param lockedAt        when the conversion was locked
 * @param railPerformedFx whether the rail did the conversion rather than the group funding it
 */
public record FxQuote(
        UUID id,
        FxRate rate,
        Money baseAmount,
        Money convertedAmount,
        long residualScaled,
        int residualScale,
        Instant lockedAt,
        boolean railPerformedFx) {

    public FxQuote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(baseAmount, "baseAmount");
        Objects.requireNonNull(convertedAmount, "convertedAmount");
        Objects.requireNonNull(lockedAt, "lockedAt");

        if (!baseAmount.currency().equals(rate.base())) {
            throw new InvalidRateException(baseAmount.currency(), rate.base());
        }
        if (!convertedAmount.currency().equals(rate.quote())) {
            throw new InvalidRateException(
                    "converted amount is in " + convertedAmount.currency().code()
                            + " but the rate quotes " + rate.quote().code());
        }
    }

    /**
     * Locks a rate against an amount, refusing a rate that is not usable at {@code lockedAt}.
     *
     * <p>Fails closed rather than fixing up. A stale or future-dated rate cannot be evidence of the
     * price a creditor is being promised, and the alternative to an exception here is a transfer for
     * the wrong amount that nobody notices until reconciliation (ADR 0013, point 6).
     *
     * @throws StaleRateException when the rate was not usable at {@code lockedAt}
     */
    public static FxQuote lock(
            UUID id, Money baseAmount, FxRate rate, Instant lockedAt, boolean railPerformedFx) {
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(lockedAt, "lockedAt");

        if (!rate.isUsableAt(lockedAt)) {
            throw new StaleRateException(
                    rate.base(), rate.quote(), rate.quotedAt(), rate.expiresAt(), lockedAt);
        }

        FxConversion conversion = FxConverters.exact().convert(baseAmount, rate);
        return new FxQuote(
                id,
                rate,
                baseAmount,
                conversion.converted(),
                conversion.residualScaled(),
                conversion.residualScale(),
                lockedAt,
                railPerformedFx);
    }

    /**
     * This quote viewed as a conversion.
     *
     * <p>Lets callers that only need the amount and the residual avoid duplicating the scaled-residual
     * formatting, and keeps one implementation of it in the codebase.
     */
    public FxConversion asConversion() {
        return new FxConversion(convertedAmount, residualScaled, residualScale);
    }

    /** Whether rounding was exact, so there is nothing for the clearing account to absorb. */
    public boolean residualIsZero() {
        return residualScaled == 0L;
    }

    @Override
    public String toString() {
        return "quote " + id + " " + rate.pair() + " " + baseAmount + " -> " + asConversion()
                + (railPerformedFx ? " (rail-performed fx)" : "")
                + " locked " + lockedAt;
    }
}
