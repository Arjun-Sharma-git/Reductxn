package io.reductxn.settlement.api;

import io.reductxn.netting.api.TaxRule;

import java.util.List;

/**
 * Composes a multicurrency cycle into per-currency settlement plans (ADR 0012).
 *
 * <p>The netting engine is not extended by this. Each partition is handed to the existing
 * single-currency engine unchanged, which is what keeps N1-N9 true per currency rather than true only
 * by redesign.
 *
 * @see SettlementPlans#perCurrency()
 */
@FunctionalInterface
public interface SettlementPlanner {

    /**
     * Partitions a cycle by settlement currency and nets each partition independently.
     *
     * @param obligations      the cycle's obligations, each already carrying its locked quote if cross
     * @param taxRuleResolver  resolves the rule per currency partition
     * @return one plan per currency present, ordered by currency code
     */
    SettlementPlan plan(List<CycleObligation> obligations, TaxRuleResolver taxRuleResolver);

    /** Plans with one tax rule applied to every partition. */
    default SettlementPlan plan(List<CycleObligation> obligations, TaxRule taxRule) {
        return plan(obligations, TaxRuleResolver.fixed(taxRule));
    }
}
