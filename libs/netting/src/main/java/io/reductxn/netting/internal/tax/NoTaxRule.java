package io.reductxn.netting.internal.tax;

import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.TaxRule;

/**
 * No tax, as a rule rather than as a null check.
 *
 * <p>Making the untaxed case a real rule means the engine has exactly one code path and no special
 * case to forget. Used by record-only deployments and by netting previews.
 */
public final class NoTaxRule implements TaxRule {

    /** Stateless, so a single instance is shared. */
    public static final NoTaxRule INSTANCE = new NoTaxRule();

    private NoTaxRule() {
    }

    @Override
    public Money assess(Money base) {
        return Money.zero(base.currency());
    }

    @Override
    public Objective objective() {
        return Objective.MIN_TRANSFERS;
    }
}
