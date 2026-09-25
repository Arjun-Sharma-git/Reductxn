package io.reductxn.planning.api;

import io.reductxn.planning.internal.PerCurrencySettlementPlanner;

/**
 * Public factories for settlement planners (RULE B10).
 *
 * <p>Mirrors {@code NettingEngines} and {@code TaxRules}: the implementation stays private and callers
 * see only the interface, so "public surface" is enforced rather than merely intended.
 */
public final class SettlementPlans {

    /** Stateless, so one instance is shared. */
    private static final SettlementPlanner PER_CURRENCY = new PerCurrencySettlementPlanner();

    private SettlementPlans() {
    }

    /**
     * The planner that partitions by currency and nets each partition with the single-currency engine.
     *
     * <p>There is only one, and deliberately no multicurrency engine alternative. Netting across
     * currencies by valuing everything in a base currency destroys per-currency zero-sum, so the plan
     * would no longer be settlable in the currencies members actually hold (ADR 0012, alternatives).
     */
    public static SettlementPlanner perCurrency() {
        return PER_CURRENCY;
    }
}
