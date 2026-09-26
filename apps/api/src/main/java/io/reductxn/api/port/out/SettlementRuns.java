package io.reductxn.api.port.out;

import io.reductxn.api.port.in.RunReport;

import java.util.Optional;
import java.util.UUID;

/**
 * Reading a settlement run: this module's projection over {@code workers/settlement}'s rows.
 *
 * <p>The arrangement is the read twin of {@code Approvals}. {@code settlement_runs}, {@code settlement_partitions},
 * {@code settlement_transfers} and {@code settlement_decisions} belong to the worker - its migrations are the
 * {@code 5xx} range, this module owns none of them - and this deployable reads them because the member's surface is
 * here and the run is there, the two modules may not depend on each other (RULE B6) and there is no broker in the
 * build. The precedent is the same one the write direction used, and older than both: this module's netting preview
 * already reads {@code settlement_runs} and {@code settlement_run_obligations} to exclude commitments a settled run
 * has discharged.
 *
 * <p><strong>What a projection may do is read rows and count them.</strong> It may not compute what the rows mean:
 * quorum, the group's external requirement and the reporting-currency totals are the worker's judgements, and
 * re-deriving them here would be a second implementation of the run's arithmetic (ADR 0021). Counting this run's
 * transfers per currency is on the right side of that line - it is a count of rows, not a plan.
 *
 * <p>The rows' shape is pinned from both sides rather than by a shared type, because RULE B6 leaves no room for
 * one: {@code ReadRunApiTest} here, and the worker's own tests for what it writes. A column change that broke this
 * projection fails the test that reads it.
 */
public interface SettlementRuns {

    /**
     * The run and everything this module can honestly say about it, or empty when no such run exists.
     *
     * <p>One call rather than four, so a reader gets one view of the rows instead of a run and its transfers read at
     * two instants - and so the adapter can decide how many queries that takes.
     */
    Optional<RunReport> findById(UUID runId);
}
