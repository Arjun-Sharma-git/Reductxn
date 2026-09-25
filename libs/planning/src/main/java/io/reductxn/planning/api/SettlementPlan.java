package io.reductxn.planning.api;

import io.reductxn.ledger.api.Currency;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole plan for a cycle: one partition per currency present.
 *
 * <p>Partitions are ordered by currency code, not by discovery order, so two runs over the same cycle
 * produce byte-identical plans and an auditor can diff them. Determinism is a requirement here rather
 * than a nicety: a preview the group approved and a run that executes it must be the same object.
 *
 * @param partitions one per currency, sorted by currency code, never empty unless the cycle was
 */
public record SettlementPlan(List<CurrencyPlan> partitions) {

    public SettlementPlan {
        Objects.requireNonNull(partitions, "partitions");
        partitions = List.copyOf(partitions);

        // An ordering that drifts would make the plan non-deterministic while still looking correct.
        // Checked on construction rather than trusted to the caller.
        for (int index = 1; index < partitions.size(); index++) {
            String previous = partitions.get(index - 1).currency().code();
            String current = partitions.get(index).currency().code();
            if (previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException(
                        "partitions must be sorted by currency code and unique, but " + previous
                                + " precedes " + current);
            }
        }
    }

    /** The currencies this cycle touches, in plan order. */
    public List<Currency> currencies() {
        return partitions.stream().map(CurrencyPlan::currency).toList();
    }

    /** Whether the cycle had no obligations at all. */
    public boolean isEmpty() {
        return partitions.isEmpty();
    }

    /** The partition for a currency, if the cycle touched it. */
    public Optional<CurrencyPlan> partitionFor(Currency currency) {
        return partitions.stream().filter(plan -> plan.currency().equals(currency)).findFirst();
    }

    /** The partitions that cannot settle until the group funds them from outside (RULE S4). */
    public List<CurrencyPlan> partitionsRequiringExternalFunding() {
        return partitions.stream().filter(CurrencyPlan::requiresExternalFunding).toList();
    }

    /**
     * Whether any currency needs external funding.
     *
     * <p>This is the condition that moves a run to {@code awaiting_fx_funding}. It is a real blocking
     * state, not a warning (RULE S4).
     */
    public boolean requiresExternalFunding() {
        return partitions.stream().anyMatch(CurrencyPlan::requiresExternalFunding);
    }

    /**
     * Whether netting removed nothing in every partition, so the cycle gains the group nothing.
     *
     * <p>Honest reporting: a group whose obligations are all cross-currency and each with its own
     * counterparty may pay more than it saved, and the product should be able to say so.
     */
    public boolean everyPartitionSettlesDirectly() {
        return partitions.stream().allMatch(plan -> plan.netting().taxSaved().isZero());
    }

    @Override
    public String toString() {
        return "plan for " + partitions.size() + " currencies " + partitions;
    }
}
