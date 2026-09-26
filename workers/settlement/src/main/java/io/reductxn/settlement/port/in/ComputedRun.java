package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.List;
import java.util.Objects;

/**
 * What a run computed: the run itself, and one outcome per currency it touches.
 *
 * <p>The same shape as {@code SettlementPlan}, deliberately, and the correspondence is the point: the plan is
 * what {@code libs/planning} decided, this is what the run stored and will report. If the two ever disagreed,
 * a group would be approving one thing while the run executed another - so the adapter takes the plan and
 * writes it, and the summaries here are derived from the stored rows rather than from the plan a second time.
 *
 * <p>Partitions are ordered by currency code, as in the plan, so two readings of the same run produce the same
 * report and an auditor can diff them.
 *
 * @param run        the run as it now stands
 * @param partitions one per currency, sorted by currency code
 */
public record ComputedRun(SettlementRun run, List<PartitionOutcome> partitions) {

    public ComputedRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(partitions, "partitions");
        partitions = List.copyOf(partitions);

        for (int index = 1; index < partitions.size(); index++) {
            String previous = partitions.get(index - 1).currency().code();
            String current = partitions.get(index).currency().code();
            if (previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException(
                        "partitions must be sorted by currency code and unique, but " + previous
                                + " precedes " + current + ". An order that drifts would make the same run "
                                + "report differently on two readings");
            }
        }
    }

    /** The currencies this run settles, in report order. */
    public List<Currency> currencies() {
        return partitions.stream().map(PartitionOutcome::currency).toList();
    }

    /** What the group must supply from outside, per currency, for the partitions that need it. */
    public List<Money> externalRequirements() {
        return partitions.stream()
                .filter(PartitionOutcome::requiresExternalFunding)
                .map(PartitionOutcome::externalRequirement)
                .toList();
    }

    /**
     * Whether the run is blocked on funding (RULE S4).
     *
     * <p>Asked of the run's status rather than recomputed from the partitions: the status is the decision, and a
     * second derivation of it is a second answer waiting to disagree.
     */
    public boolean requiresExternalFunding() {
        return run.requiresExternalFunding();
    }
}
