package io.reductxn.settlement.port.out;

import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.GateConclusion;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.in.SilentTransfer;
import io.reductxn.settlement.port.in.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The run aggregate: the plan it was computed from, and everything that has happened to it since.
 *
 * <p><strong>One port for seven tables, because they are one decision written down</strong> (RULE B17). The
 * cycle, the run, its partitions, its positions, its transfers, its decisions and its funding are not seven
 * repositories: they are the run, and a caller that could write one without the others could record a run whose
 * approvals belong to a plan that was never stored. {@code open} writes five of them in one transaction for
 * exactly that reason.
 *
 * <p><strong>{@link #open} takes the plan as a parameter rather than computing it.</strong> That is the split the
 * architecture is built on: the plan is {@code libs/planning}'s, the run is this module's, and an adapter that
 * ran the netting engine would put a business decision behind a repository's method signature.
 *
 * <p><strong>Reads answer with {@link ComputedRun}, and it is read back from the rows.</strong> The report is
 * derived from what was stored rather than from the plan that was passed in, so there is one derivation rather
 * than two, and it is exercised on the way out of every write. Quorum and funding are part of that report
 * because they are part of the run: a caller that recomputed them from the plan and the approvals would be the
 * second derivation this avoids.
 *
 * <p><strong>The writers return nothing, deliberately.</strong> A command that wrote one transfer's state and
 * rebuilt the whole report would query the run, its partitions, its transfers, its approvals and its funding
 * for an answer nobody asked for - and execution moves every transfer in turn. The use case reads the report
 * once, when it has finished, through {@link #findRunById}.
 *
 * <p>Every writer takes its instant as a parameter for the same reason {@code open} takes the run: the clock is
 * the use case's, so nothing below {@code application} has to be trusted with one.
 */
public interface Runs {

    /**
     * The run over this group's cycle at this instant, with its plan and its gates, if one has been opened.
     *
     * <p>This is what makes {@link io.reductxn.settlement.port.in.ComputeRun#compute} idempotent: the cycle is
     * keyed by {@code (group, as-of)}, so a retry finds the run it already opened instead of planning again.
     */
    Optional<ComputedRun> findRun(UUID groupId, Instant asOf);

    /**
     * The runs a driver may act on: those in a state where progress is possible, least recently attempted first.
     *
     * <p>The states are the three that wait on something - {@code awaiting_approval}, {@code awaiting_fx_funding}
     * and {@code executing} - rather than everything that is not terminal. {@code pending} and {@code computing}
     * exist only inside the transaction that opens a run, and {@code done}, {@code failed} and {@code rejected}
     * are finished; driving any of those five would be asking a question whose answer cannot change.
     *
     * <p><strong>Ordered by {@code driven_at}, nulls first, and that ordering is the anti-starvation
     * mechanism.</strong> A run a gate keeps refusing is attempted once per window and then goes to the back of
     * the queue, so a group that never approves its run cannot keep a different run - one that is ready to settle
     * - from ever being looked at. Ordering by creation would do exactly that.
     *
     * @param now             what "now" means for a transfer's own wait: a run whose transfers are all waiting is
     *                        not due, however long it has been since it was last driven
     * @param attemptedBefore a run attempted at or after this instant is not due
     * @param batchSize       the most runs to return; a backlog drains over several passes rather than in one
     */
    List<SettlementRun> dueForDriving(Instant now, Instant attemptedBefore, int batchSize);

    /**
     * Records that an attempt was made, before it is made.
     *
     * <p>Written first for the reason section 4.2 gives for every other write here: a driver that dies mid-pass
     * leaves a mark rather than a run that looks untouched, so the next pass does not immediately re-attempt
     * everything the dead one was in the middle of.
     *
     * <p>It records the attempt and not its outcome, deliberately. What happened is already recorded where it
     * happened - the transfer's state, the run's status, the rail's own words in {@code rail_detail} - and a
     * second copy here would be a claim that can disagree with them.
     */
    void markDriven(UUID runId, Instant attemptedAt);

    /**
     * The run with this id, with its plan and its gates.
     *
     * <p>What the three commands that act on an existing run read first, and what each of them answers with. An
     * empty answer is not "no plan yet" - a caller naming a run id is past that question - so the use cases turn
     * it into a refusal rather than an empty report.
     */
    Optional<ComputedRun> findRunById(UUID runId);

    /**
     * Opens the cycle if it does not exist, records the run, writes the plan and the commitments it covers, and
     * answers with the plan as stored.
     *
     * <p>One transaction, because half a plan is worse than none - a run whose transfers were written without
     * its positions could not be checked against the ledger, and a run whose positions were written without its
     * transfers could not be executed.
     *
     * <p><strong>{@code settledObligationIds} is the same list the plan was computed from.</strong> A run that
     * recorded its plan but not the commitments it netted could not answer which promises it discharged, and a
     * later cycle would net them again - the double settlement that is the whole reason this is stored rather
     * than derived (see {@code V504}). They are written here rather than by a separate call so that a run and
     * the commitments it covers cannot exist apart.
     *
     * @param run                  the run as the use case decided it, including its status
     * @param plan                 the plan to store, unchanged
     * @param settledObligationIds the commitments the plan was computed from, which this run settles when its
     *                             transfers are confirmed
     */
    ComputedRun open(SettlementRun run, SettlementPlan plan, List<UUID> settledObligationIds);

    /**
     * Moves a run to another state.
     *
     * <p>The whole run rather than an id and a status: the caller already holds the run it read, and passing the
     * value it intends to store makes the write a copy of a known state rather than a string that could disagree
     * with the row it came from. The adapter does not judge the transition - which ones are legal is a rule, and
     * a rule belongs in the use case where it can be tested without a database.
     */
    void updateStatus(SettlementRun run);

    /**
     * Records a member's answer (RULE S1).
     *
     * <p>An insert rather than an upsert, because a decision is final: the primary key on
     * {@code (run_id, member_id)} is what makes a second answer impossible at the database and not only in the
     * guard that checked first. The use case refuses the second answer before reaching here, so a constraint
     * violation would mean two callers raced - and the loser of that race is right to fail loudly.
     */
    void recordDecision(RunDecision decision, Instant decidedAt);

    /**
     * Records currency arriving from outside (RULE S4).
     *
     * <p>The insert is a no-op when the reference has been used before, which is the idempotency RULE S2 asks of
     * every path that can half-happen: a caller that timed out and retried records one funding, and the report
     * reads one. That is why the reference is required and unique rather than optional and informational.
     */
    void recordFunding(FundingRequest request, Instant fundedAt);

    /**
     * Writes what the run's gates concluded, replacing whatever the last look concluded.
     *
     * <p><strong>For a reader in another deployable, not for this module.</strong> Everything here is derivable
     * from the run's rows, and this module does derive it - {@link #findRunById} answers with the gates read from
     * the rows. What a reader elsewhere cannot do is derive it again without implementing the run's arithmetic a
     * second time (ADR 0021), so the verdict is written down where it can be projected (ADR 0023).
     *
     * <p><strong>One row per run, and the write is the whole conclusion.</strong> The verdict fields are an upsert
     * on the run and the funding gaps are rows that are replaced rather than merged: a gap that has been covered
     * must disappear, and an update that only inserted would leave a run reporting a shortfall it no longer has.
     * All of it belongs in one transaction, which is the caller's - {@code ExecuteRunService} opens it - so a reader
     * never sees a verdict with the previous verdict's gaps under it.
     *
     * <p>Written unconditionally on every look rather than only when it changed, because {@code concludedAt} means
     * "when the worker last read these gates" and a conditional write would quietly change that to "when they first
     * said this" - a different, less useful fact.
     */
    void recordGates(GateConclusion conclusion);

    /**
     * Writes one transfer's state, and what the rail said about it.
     *
     * <p>The single-transfer write the spec's section 4.2 asks for: every state transition is persisted before
     * the next call is made. It takes the transfer's id rather than the transfer itself, because the caller
     * holds a {@code PlannedTransfer} only as it was read and the rail's answer is what changed.
     *
     * @param railDetail what the rail said, or null when it has not said anything
     */
    void markTransfer(UUID transferId, TransferStatus status, String railDetail, Instant at);

    /**
     * Records that we are about to offer this transfer to the rail, before the call is made.
     *
     * <p>Separate from {@link #markTransfer} because it is the one write that is not an outcome: the retry
     * counter must be left alone here. Clearing it before the call would mean an attempt that then went
     * unanswered counted as the first failure every time, so the backoff would restart at its base and never
     * grow - which is the whole of what exponential means.
     */
    void markTransferAttempted(UUID transferId, TransferStatus status, Instant at);

    /**
     * Records a call the rail did not answer: the count grows, and the transfer is held back until
     * {@code nextAttemptAt}.
     *
     * <p>The count is passed rather than incremented in SQL, so that the arithmetic that decides a wait lives in
     * one place - the use case's {@code Backoff} - and the value written is the same value the wait was derived
     * from. An adapter that incremented its own counter could disagree with the delay stored beside it.
     *
     * @param status              what the transfer stays - {@code authorized} for one never offered,
     *                            {@code submitted} for one the rail already has
     * @param consecutiveUnanswered the count after this failure, which is what {@code nextAttemptAt} was
     *                            computed from
     * @param unansweredSince     when this spell of silence began: the caller keeps the start it already had, and
     *                            starts one here when there was none. Passed rather than decided in SQL for the
     *                            reason the count is - the two facts describe one spell, and an adapter deciding
     *                            where it began could disagree with the count stored beside it
     */
    void markTransferUnanswered(UUID transferId, TransferStatus status, String railDetail, Instant at,
            int consecutiveUnanswered, Instant nextAttemptAt, Instant unansweredSince);

    /**
     * The transfers whose rail has been silent since at or before {@code cutOff}: what an escalation policy is
     * about, oldest silence first.
     *
     * <p><strong>The cut-off is the caller's, and the policy is not.</strong> How long a rail may go unanswered
     * before somebody has to look is a judgement about a deployment ({@code Escalation}), so the policy computes
     * the instant and this query compares against it - the same arrangement as {@code driveDue}'s window, where
     * the staleness that matters is configuration and the query is a comparison.
     *
     * <p>Transfers with an answer are not in it, because an answer clears {@code unanswered_since}: the result is
     * exactly the transfers somebody has to decide about, and nothing else.
     *
     * @param cutOff a transfer is included when its silence began at or before this instant
     * @param limit  the most to return, so a report cannot become a novel. The oldest are taken first, which is
     *               the order a broken rail should be looked at in
     */
    List<SilentTransfer> findSilentTransfers(Instant cutOff, int limit);

    /**
     * How many transfers are silent at or before {@code cutOff}, however many that is.
     *
     * <p>Separate from {@link #findSilentTransfers} because a report says both "how bad is this" and "which ones":
     * a list truncated by its limit would understate the first, and an operator deciding whether to open the rail's
     * status page needs the number rather than the first twenty.
     */
    int countSilentTransfers(Instant cutOff);
}
