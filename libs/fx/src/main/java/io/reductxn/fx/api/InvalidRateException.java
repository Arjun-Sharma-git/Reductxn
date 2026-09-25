package io.reductxn.fx.api;

import io.reductxn.ledger.api.Currency;

/**
 * Raised when a rate is missing, malformed, or not applicable to the amount being converted.
 *
 * <p>This is a programming or data fault rather than an expected market condition, so it throws. A
 * conversion performed against a mismatched rate would produce a plausible-looking wrong number.
 *
 * <p>Currency codes are held as strings rather than {@code Currency} values, matching
 * {@code ledger.api.CurrencyMismatchException} and for a concrete reason: {@code Currency} is not
 * {@code Serializable}, so a typed field would either fail serialization or deserialize as null.
 */
public final class InvalidRateException extends FxException {

    private static final long serialVersionUID = 1L;

    /** The stable code for this failure. See {@code DomainCodes}. */
    public static final String CODE = "fx.invalid_rate";

    private final String amountCurrencyCode;
    private final String rateBaseCode;
    private final String detail;

    public InvalidRateException(Currency amountCurrency, Currency rateBase) {
        super(CODE,
                "rate base " + rateBase.code() + " does not match the amount's currency "
                        + amountCurrency.code());
        this.amountCurrencyCode = amountCurrency.code();
        this.rateBaseCode = rateBase.code();
        this.detail = "base mismatch";
    }

    /** A malformed rate definition, for example a zero or negative rate. */
    public InvalidRateException(String detail) {
        super(CODE, detail);
        this.amountCurrencyCode = null;
        this.rateBaseCode = null;
        this.detail = detail;
    }

    /** Code of the currency of the amount that was to be converted, when known. */
    public String amountCurrencyCode() {
        return amountCurrencyCode;
    }

    /** Code of the base currency the rate was denominated in, when known. */
    public String rateBaseCode() {
        return rateBaseCode;
    }

    public String detail() {
        return detail;
    }
}
