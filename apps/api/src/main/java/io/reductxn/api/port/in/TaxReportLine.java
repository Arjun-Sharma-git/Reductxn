package io.reductxn.api.port.in;

import java.util.Objects;

/**
 * One currency's assessed tax over a period, and how many plans it is made of.
 *
 * <p><strong>Raw minor units rather than {@code Money}.</strong> A {@code Money} carries an exponent, and every other
 * port that reports a stored plan's figure hands the mapper the minor units and the code, because the wire's
 * {@code Money} schema carries no exponent either (the client reads it from {@code /currencies}). Wrapping these in
 * {@code Money} here would mean loading a currency catalogue to build a response field that does not carry what was
 * loaded.
 *
 * <p>{@code grossTaxMinor} is not stored anywhere and is not summed from a stored column: it is this line's
 * {@code netTaxMinor} plus its {@code taxSavedMinor}, which is how the preview's mapper recovers the same figure. A
 * plan's gross tax is the tax it assesses plus the tax netting saved it, and adding them back is what keeps the three
 * numbers from ever disagreeing.
 *
 * <p>{@code runCount} is what makes this a report rather than a number: it says how many plans the line is made of,
 * so a reader can tell one cycle's tax from a year's.
 */
public record TaxReportLine(
        String currency,
        long grossTaxMinor,
        long netTaxMinor,
        long taxSavedMinor,
        int runCount) {

    public TaxReportLine {
        Objects.requireNonNull(currency, "currency");
    }

    /** The line for one currency's totals, with the gross recovered from the other two. */
    public static TaxReportLine of(String currency, long netTaxMinor, long taxSavedMinor, int runCount) {
        return new TaxReportLine(currency, netTaxMinor + taxSavedMinor, netTaxMinor, taxSavedMinor, runCount);
    }
}
