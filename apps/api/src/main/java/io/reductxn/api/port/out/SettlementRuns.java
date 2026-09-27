package io.reductxn.api.port.out;

import io.reductxn.api.port.in.RunReport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading a settlement run: this module's projection over {@code workers/settlement}'s rows.
 *
 * <p>The arrangement is the read twin of {@code Decisions}. {@code settlement_runs}, {@code settlement_partitions},
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
     * The run and everything this module can honestly say about it, **if the caller is a member of the group it
     * settles** - and empty otherwise.
     *
     * <p>One call rather than four, so a reader gets one view of the rows instead of a run and its transfers read at
     * two instants - and so the adapter can decide how many queries that takes.
     *
     * <p><strong>The membership condition is here rather than in the use case, and it is the same doctrine
     * {@link Groups} states for a group.</strong> A service that read the run and then checked membership would have to
     * trust itself to remember to check, and would read rows it may not show to answer a question it has not asked yet.
     * Narrowing inside the statement means a run in a group the caller is not in cannot be read at all - and it is what
     * makes "no such run" and "not yours" one answer, because the query cannot tell them apart either (ADR 0054).
     *
     * <p><strong>Why this one asks the store and the nine group reads ask {@code Groups.isMember}:</strong> a run's
     * group is only knowable *from* the run, so a check that came first would need a second query whose only purpose was
     * to be asked first. This is the `ReadActivityService` argument in the other direction - there, the check is a
     * separate statement because the three sources do not contain a group's members; here the join is one clause.
     */
    Optional<RunReport> findFor(UUID runId, UUID callerId);

    /**
     * A group's runs, newest first, at most {@code limit} of them.
     *
     * <p><strong>The order is the schema's, not this module's preference.</strong> {@code V501} creates
     * {@code settlement_runs_by_group (group_id, created_at DESC)}, so "the group's runs, newest first" is a column
     * order the migration already chose - and a read model that invented a different one would walk an index it has
     * for nothing. A tie on {@code created_at} is broken by the run's id, because a plan that appeared in a different
     * order on two reads is the problem the transfer ordering was already careful about.
     *
     * <p><strong>Each row is a full report, and that is deliberate rather than lazy.</strong> A list row a member
     * chooses from has to say what the plan moves and whether it can proceed, which is the partitions and the gates -
     * the two things the projections below already read. A thinner "summary" type would be a shape invented here for
     * one screen, and the client would then need a second read to answer "how much is this one?" before a member
     * could pick it. What that costs is a handful of queries per listed run in one transaction, which is what the
     * limit is for.
     *
     * <p><strong>Group ids are read, never verified here, and membership is the use case's condition.</strong> This
     * method answers what a group's runs are; whether the caller may see them is asked before it is called
     * ({@code Groups.isMember}, ADR 0054), because the group id is the caller's own argument and the check costs one
     * statement rather than a join. The paragraph that used to be here said "groups are not tables in this build, so a
     * group nobody has heard of reads back as a group with no runs" - true when it was written, false since ADR 0043,
     * and the reason every one of these reads now refuses a group that is not the caller's.
     */
    List<RunReport> listByGroup(UUID groupId, int limit);
}
