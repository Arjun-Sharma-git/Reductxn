package io.reductxn.settlement.internal;

import io.reductxn.ledger.api.Currency;
import io.reductxn.netting.api.NettingEngine;
import io.reductxn.netting.api.NettingEngines;
import io.reductxn.netting.api.NettingRequest;
import io.reductxn.netting.api.Obligation;
import io.reductxn.settlement.api.CrossCurrencyObligation;
import io.reductxn.settlement.api.CurrencyPlan;
import io.reductxn.settlement.api.CycleObligation;
import io.reductxn.settlement.api.FxClearing;
import io.reductxn.settlement.api.SettlementPlan;
import io.reductxn.settlement.api.SettlementPlanner;
import io.reductxn.settlement.api.SingleCurrencyObligation;
import io.reductxn.settlement.api.TaxRuleResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Partitions a cycle by currency and hands each partition to the unchanged single-currency engine
 * (ADR 0012 point 1).
 *
 * <p>Pure and deterministic: same obligations, same plan. No clock, no provider, no ordering that
 * depends on how the caller happened to list things.
 *
 * <p>Stateless, so one instance is shared.
 */
public final class PerCurrencySettlementPlanner implements SettlementPlanner {

    private static final NettingEngine ENGINE = NettingEngines.greedy();

    /**
     * Orders partitions by currency code, then by exponent.
     *
     * <p>The exponent is part of the ordering deliberately. A comparator on the code alone would treat
     * {@code (INR, 2)} and {@code (INR, 0)} as the same key and silently merge two partitions with
     * different minor units into one, which corrupts amounts without failing. With the exponent
     * included they stay distinct, and {@code SettlementPlan} then rejects the pair as incoherent -
     * one currency cannot have two exponents in one cycle.
     */
    private static final Comparator<Currency> BY_CODE_THEN_EXPONENT =
            Comparator.comparing(Currency::code).thenComparingInt(Currency::minorUnitExponent);

    /**
     * A total order over a partition's legs.
     *
     * <p>Needed because the netting engine is deterministic only for <em>identical</em> input, and the
     * pairwise tier emits its transfers in the order the obligations arrived. A cycle's obligations are
     * a set: they come out of a database, and row order is not guaranteed. Without normalising here,
     * the same cycle could produce two plans that are equal in every amount and different as values -
     * which would mean a preview the group approved was not literally the plan that executed.
     *
     * <p>Sorting here rather than in the engine is deliberate. ADR 0012 says the engine does not
     * change, and it does not need to: its caller can simply stop handing it an arbitrary order.
     */
    private static final Comparator<Obligation> BY_PARTICIPANTS_THEN_AMOUNT =
            Comparator.comparing(Obligation::debtorId)
                    .thenComparing(Obligation::creditorId)
                    .thenComparingLong(leg -> leg.amount().amountMinor());

    @Override
    public SettlementPlan plan(List<CycleObligation> obligations, TaxRuleResolver taxRuleResolver) {
        Objects.requireNonNull(obligations, "obligations");
        Objects.requireNonNull(taxRuleResolver, "taxRuleResolver");

        Map<Currency, List<Obligation>> legsByCurrency = new TreeMap<>(BY_CODE_THEN_EXPONENT);
        for (CycleObligation obligation : obligations) {
            split(obligation, legsByCurrency);
        }

        List<CurrencyPlan> partitions = new ArrayList<>(legsByCurrency.size());
        for (Map.Entry<Currency, List<Obligation>> partition : legsByCurrency.entrySet()) {
            Currency currency = partition.getKey();
            List<Obligation> legs = partition.getValue();
            legs.sort(BY_PARTICIPANTS_THEN_AMOUNT);

            NettingRequest request = new NettingRequest(
                    currency, legs, taxRuleResolver.forCurrency(currency));
            partitions.add(new CurrencyPlan(currency, ENGINE.net(request)));
        }

        return new SettlementPlan(partitions);
    }

    /**
     * Turns one obligation into one or two single-currency legs.
     *
     * <p>The switch is exhaustive over the sealed hierarchy with no default branch, so adding a third
     * kind of obligation becomes a compile error rather than an obligation that is quietly dropped.
     */
    private static void split(CycleObligation obligation, Map<Currency, List<Obligation>> into) {
        switch (obligation) {
            case SingleCurrencyObligation single -> add(into, single.amount().currency(),
                    new Obligation(single.debtorId(), single.creditorId(), single.amount()));

            case CrossCurrencyObligation cross -> {
                // The debtor's side is exact, in the currency they agreed to pay.
                add(into, cross.debtCurrency(),
                        new Obligation(cross.debtorId(), FxClearing.ACCOUNT_ID, cross.owed()));

                // The creditor's side is the converted amount, in the currency they agreed to receive.
                add(into, cross.settlementCurrency(),
                        new Obligation(FxClearing.ACCOUNT_ID, cross.creditorId(), cross.settlementAmount()));
            }
        }
    }

    private static void add(Map<Currency, List<Obligation>> into, Currency currency, Obligation leg) {
        into.computeIfAbsent(currency, key -> new ArrayList<>()).add(leg);
    }
}
