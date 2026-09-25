package io.reductxn.planning.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.netting.api.TaxRule;

import java.util.Objects;

/**
 * Resolves the tax rule in force for a partition.
 *
 * <p>A rule is keyed by `(jurisdiction, currency, effective date)`, so a multicurrency cycle resolves
 * it per currency rather than once for the whole cycle (P10, `docs/specs/tax-rules.md`).
 *
 * <p>This is a port, not a lookup. The caller resolves jurisdiction and effective date and hands back
 * a rule. Keeping it a function is what lets this module stay pure: no clock, no database, and the
 * same cycle always produces the same plan.
 */
@FunctionalInterface
public interface TaxRuleResolver {

    TaxRule forCurrency(Currency currency);

    /** One rule for every currency. For previews and tests, where the same rule is in force. */
    static TaxRuleResolver fixed(TaxRule rule) {
        Objects.requireNonNull(rule, "rule");
        return currency -> rule;
    }
}
