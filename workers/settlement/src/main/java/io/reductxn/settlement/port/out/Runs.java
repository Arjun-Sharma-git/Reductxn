package io.reductxn.settlement.port.out;

import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.SettlementRun;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The runs, and the plans they were computed from.
 *
 * <p>Owns four tables - {@code settlement_cycles}, {@code settlement_runs}, {@code net_positions} and
 * {@code settlement_transfers} - because they are one decision written down (RULE B17). The cycle is created
 * here rather than by a separate port: a cycle that exists without a run over it is a half-written fact, and
 * two ports would allow one without the other.
 *
 * <p>{@link #open} takes the plan as a parameter rather than having the adapter compute it. That is the split
 * the architecture is built on: the plan is {@code libs/planning}'s, the run is this module's, and an adapter
 * that ran the netting engine would put a business decision behind a repository's method signature.
 *
 * <p><strong>Both methods answer with {@link ComputedRun}, and it is read back from the rows.</strong> The
 * report is derived from what was stored rather than from the plan that was passed in, so there is one
 * derivation rather than two, and it is exercised on the way out of every write. An outbound port returning the
 * caller's type is the same arrangement {@code OpenObligations} makes, and for the same reason: the point of a
 * read model is to answer the question the caller asked.
 */
public interface Runs {

    /**
     * The run over this group's cycle at this instant with its plan, if one has been opened.
     *
     * <p>This is what makes {@link io.reductxn.settlement.port.in.ComputeRun#compute} idempotent: the cycle is
     * keyed by {@code (group, as-of)}, so a retry finds the run it already opened instead of planning again.
     */
    Optional<ComputedRun> findRun(UUID groupId, Instant asOf);

    /**
     * Opens the cycle if it does not exist, records the run, writes the plan, and answers with the plan as
     * stored.
     *
     * <p>One transaction, because half a plan is worse than none - a run whose transfers were written without
     * its positions could not be checked against the ledger, and a run whose positions were written without its
     * transfers could not be executed.
     *
     * @param run  the run as the use case decided it, including its status
     * @param plan the plan to store, unchanged
     */
    ComputedRun open(SettlementRun run, SettlementPlan plan);
}
