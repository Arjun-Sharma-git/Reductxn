package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of netting a group: what each member owes or is owed, and what must really move.
 *
 * <p>The headline numbers are {@link #tax()} and {@link #taxSaved()}. The second is the product's
 * reason to exist: it is the difference between taxing every pairwise obligation and taxing only
 * the transfers that netting could not remove.
 *
 * @param gross              total value of all obligations considered, before netting
 * @param net                total value of the transfers that remain after netting
 * @param tax                tax assessed on the netted transfers
 * @param taxSaved           tax that pairwise settlement would have cost, minus {@link #tax()}
 * @param grossTransferCount how many transfers naive pairwise settlement would have needed
 * @param positions          one entry per member appearing in any obligation
 * @param transfers          the instructions that settle the group, at most N-1 for N members
 * @param algorithm          which tier produced this result
 * @param provablyOptimal    whether optimality was proven rather than merely improved upon
 */
public record NettingResult(
        Money gross,
        Money net,
        Money tax,
        Money taxSaved,
        int grossTransferCount,
        List<NetPosition> positions,
        List<TransferInstruction> transfers,
        Algorithm algorithm,
        boolean provablyOptimal) {

    public NettingResult {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(taxSaved, "taxSaved");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions"));
        transfers = List.copyOf(Objects.requireNonNull(transfers, "transfers"));
        Objects.requireNonNull(algorithm, "algorithm");
    }

    /** Which plan produced this result. */
    public enum Algorithm {

        /** Tier 1 netting: always correct, at most N-1 transfers. */
        GREEDY,

        /**
         * Settling the obligations directly, one transfer each.
         *
         * <p>Chosen when netting would cost more than simply paying what is owed. Under a flat
         * per-transfer tax that happens whenever the obligation set is sparse, because there is
         * nothing for netting to remove and re-matching unrelated members can invent transfers
         * (ADR 0011). A result with this algorithm means the group pays no more than it would have
         * without reductxn, and {@code taxSaved} is zero.
         */
        PAIRWISE,

        /** Tier 2: zero-sum subset partitioning on top of Tier 1. */
        SUBSET_PARTITION,

        /** Tier 2: mixed-integer programme, the strongest guarantee available. */
        MIP
    }

    /** Number of real transfers required. */
    public int netTransferCount() {
        return transfers.size();
    }

    /** How many transfers netting removed: the count the tax saving comes from. */
    public int transfersAvoided() {
        return grossTransferCount - transfers.size();
    }
}
