package io.reductxn.api.web.dto;

/**
 * The {@code TaxReportLine} schema from {@code contracts/openapi/reductxn-api.yaml}.
 *
 * <p>One currency's assessed tax for a period. The three amounts are the contract's {@code Money}, so each carries its
 * own currency code and no exponent - the client reads exponents from the currency catalogue, which is what the
 * contract says that endpoint is for. There is no total across currencies here and no field that could hold one:
 * adding rupees to yen is not addition (ADR 0012), and the operation's parameter list deliberately offers no reporting
 * currency to convert into.
 */
public record TaxReportLineResponse(
        String currency,
        MoneyDto grossTax,
        MoneyDto netTax,
        MoneyDto taxSaved,
        int runCount) {
}
