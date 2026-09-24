package io.reductxn.ledger.api;

import java.util.Objects;

/**
 * A currency and its minor-unit exponent.
 *
 * <p>The exponent is data, not a constant hidden inside a formatter, so presentation never has to
 * guess how many fractional digits an amount carries. INR has exponent 2, JPY has exponent 0.
 *
 * @param code              the currency code, for example INR, USD, or a CBDC ledger code
 * @param minorUnitExponent number of fractional digits, 0 to 8
 */
public record Currency(String code, int minorUnitExponent) {

    public Currency {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("currency code must not be blank");
        }
        if (minorUnitExponent < 0 || minorUnitExponent > 8) {
            throw new IllegalArgumentException("minor unit exponent out of range: " + minorUnitExponent);
        }
    }

    public static Currency of(String code, int minorUnitExponent) {
        return new Currency(code, minorUnitExponent);
    }

    /**
     * Formats an amount of minor units for display.
     *
     * <p>Deliberately string-based: no division is performed, so no representation error can be
     * introduced at the last moment before a number reaches a user.
     */
    public String format(long amountMinor) {
        boolean negative = amountMinor < 0;
        String digits = Long.toString(Math.abs(amountMinor));
        String rendered;
        if (minorUnitExponent == 0) {
            rendered = digits;
        } else {
            String padded = "0".repeat(Math.max(0, minorUnitExponent + 1 - digits.length())) + digits;
            int split = padded.length() - minorUnitExponent;
            rendered = padded.substring(0, split) + "." + padded.substring(split);
        }
        return negative ? "-" + rendered : rendered;
    }
}
