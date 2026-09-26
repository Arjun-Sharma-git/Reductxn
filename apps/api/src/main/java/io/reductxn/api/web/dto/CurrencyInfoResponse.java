package io.reductxn.api.web.dto;

import io.reductxn.ledger.api.Currency;

/**
 * The contract's `CurrencyInfo`: a code and the exponent needed to render an amount in it.
 *
 * <p>No mapper method and no service type of its own - two fields copied from the ledger's {@link Currency} is a
 * helper that would hide which fields the wire carries, and this DTO is where that decision belongs.
 */
public record CurrencyInfoResponse(String code, int minorUnitExponent) {

    public static CurrencyInfoResponse of(Currency currency) {
        return new CurrencyInfoResponse(currency.code(), currency.minorUnitExponent());
    }
}
