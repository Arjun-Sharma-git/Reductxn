package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.List;
import java.util.Objects;

/**
 * A run as it stands, with the plan it will execute and the two gates in front of it.
 *
 * <p>The same shape as {@code SettlementPlan}, extended by everything the run has since decided or been told:
 * who has approved, what has been funded, and what each transfer's state is. If the plan and this ever
 * disagreed about an amount, a group would be approving one thing while the run executed another - so the
 * adapter takes the plan, writes it, and derives every figure here from the stored rows rather than from the
 * plan a second time.
 *
 * <p>Partitions are ordered by currency code and transfers by rail reference, so two readings of the same run
 * produce the same report and an auditor can diff them.
 *
 * <p><strong>This is the report the funding gate and the quorum are read from.</strong> Both could be
 * recomputed by a caller from the plan and the approvals, and that is precisely what must not happen: the gate
 * is a rule, and two derivations of one rule are two answers waiting to disagree.
 *
 * @param run        the run itself
 * @param partitions one per currency, sorted by currency code
 * @param transfers  every transfer the plan records, ordered by rail reference
 * @param quorum     who the run concerns and who has answered
 * @param funding    what it needs from outside and what has arrived
 */
public record ComputedRun(
        SettlementRun run,
        List<PartitionOutcome> partitions,
        List<PlannedTransfer> transfers,
        Quorum quorum,
        Funding funding) {

    public ComputedRun {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(transfers, "transfers");
        Objects.requireNonNull(quorum, "quorum");
        Objects.requireNonNull(funding, "funding");
        partitions = List.copyOf(Objects.requireNonNull(partitions, "partitions"));
        transfers = List.copyOf(transfers);

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

    /** The transfers that still have work to do, in rail-reference order. */
    public List<PlannedTransfer> unresolvedTransfers() {
        return transfers.stream().filter(transfer -> !transfer.status().isResolved()).toList();
    }
}

