package io.reductxn.fx.api;

import io.reductxn.ledger.api.Money;

/**
 * Raised when a non-zero amount would convert to zero minor units in the target currency.
 *
 * <p>Refused rather than performed. Silently producing zero would make a real debt vanish and would
 * break N3 downstream, because a settlement instruction of zero is rejected as a non-positive
 * transfer. A user-facing refusal with a clear code is better than a debt that disappears.
 *
 * <p>Reachable in practice rather than theoretical: JPY has exponent 0, so an amount of a
 * finer-grained currency can round below one yen. One paisa converts to zero yen.
 */
public final class AmountTooSmallToConvertException extends FxException {

    private static final long serialVersionUID = 1L;

    private final long amountMinor;
    private final String amountCurrencyCode;
    private final String quoteCurrencyCode;
    private final long rateScaled;
    private final int rateScale;

    public AmountTooSmallToConvertException(Money amount, FxRate rate) {
        super("fx.amount_too_small_to_convert",
                "amount " + amount.amountMinor() + " minor units of "
                        + amount.currency().code() + " converts to zero "
                        + rate.quote().code() + " at " + rate.rateScaled() + "e-" + rate.rateScale()
                        + " (" + rate.pair() + ")");
        this.amountMinor = amount.amountMinor();
        this.amountCurrencyCode = amount.currency().code();
        this.quoteCurrencyCode = rate.quote().code();
        this.rateScaled = rate.rateScaled();
        this.rateScale = rate.rateScale();
    }

    /** The amount that could not be represented, in minor units of its own currency. */
    public long amountMinor() {
        return amountMinor;
    }

    public String amountCurrencyCode() {
        return amountCurrencyCode;
    }

    public String quoteCurrencyCode() {
        return quoteCurrencyCode;
    }

    public long rateScaled() {
        return rateScaled;
    }

    public int rateScale() {
        return rateScale;
    }
}
