package io.reductxn.netting.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.CurrencyMismatchException;

import java.util.List;
import java.util.Objects;

/**
 * A request to net one group's obligations in one currency.
 *
 * <p>Construction is validating, so an engine never has to defend itself against a malformed
 * request. Multi-currency groups are netted per currency; there is no FX inside the engine.
 *
 * @param currency    the currency all obligations must be denominated in
 * @param obligations the group's open commitments for the cycle
 * @param taxRule     the rule effective for the cycle, used to assess tax and to pick the objective
 */
public record NettingRequest(Currency currency, List<Obligation> obligations, TaxRule taxRule) {

    public NettingRequest {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(taxRule, "taxRule");
        obligations = List.copyOf(Objects.requireNonNull(obligations, "obligations"));
        for (Obligation obligation : obligations) {
            if (!obligation.amount().currency().equals(currency)) {
                throw new CurrencyMismatchException(obligation.amount().currency(), currency);
            }
        }
    }

    /** A request with no tax, for previews and tests. */
    public static NettingRequest untaxed(Currency currency, List<Obligation> obligations) {
        return new NettingRequest(currency, obligations, TaxRules.none());
    }
}
