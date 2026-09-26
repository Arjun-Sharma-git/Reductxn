package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Driving the runs whose progress is possible: the thing that makes this worker a worker.
 *
 * <p>Everything else in this module's inbound surface is a reaction. {@link ComputeRun} answers a request for a
 * cycle, {@link DecideOnRun} records a member's answer, {@link RecordFxFunding} records currency arriving,
 * {@link ExecuteRun} moves a run on when somebody asks. This port is the one nobody asks for: it looks for runs
 * that <em>could</em> progress and does what each of them needs, on a cadence.
 *
 * <p><strong>Why it exists at all.</strong> A run reaches {@code awaiting_approval} and then waits. Nothing in
 * the product would move it to {@code executing} when the last approval lands, or to a rail when the currency
 * arrives, or resolve a transfer the rail never answered - those all happened only because a test called
 * {@link ExecuteRun}. A deployment would compute plans and settle nothing, which is the gap the plan's honest
 * position called "nothing triggers any of it".
 *
 * <p><strong>It drives waiting runs, and the gates decide.</strong> {@link ExecuteRun} checks quorum, funding,
 * rejection and rail support before it moves anything, so a run that is not ready is refused - and refused is
 * not an error here: it is the ordinary state of a run whose group has not finished deciding. Driving them means
 * a run starts the moment the last fact arrives, without the approver or the treasurer having to know that
 * execution exists. The cost is one refusal per run per cadence, which is why the report counts them rather than
 * logging one line each.
 *
 * <p><strong>{@code attemptedBefore} is the caller's, not this port's.</strong> How stale an attempt must be
 * before another is allowed is an operational setting (it costs rail calls), so the cadence and its window live
 * in the adapter that calls this, and the use case is told the cut-off. That also makes the threshold testable
 * without waiting a minute for it.
 *
 * <p><strong>Nothing here is a transaction, and it must not be.</strong> Driving a run submits transfers to a
 * rail, and a database transaction held across a network call is the thing section 4.2 forbids; each write the
 * driver makes is committed on its own, and the mark that a run was attempted is written before the attempt.
 * Two worker instances may therefore drive the same run at once, which is safe rather than designed away: the
 * rail is idempotent on a transfer's reference, a resolved transfer is never offered again, and a duplicate
 * ledger entry is a no-op. What the mark prevents is not corruption but a stampede.
 */
public interface DriveRuns {

    /**
     * Drives every run that has not been attempted since {@code attemptedBefore}, oldest attempt first.
     *
     * @param attemptedBefore the cut-off: a run attempted at or after this instant is left alone
     * @param batchSize       the most runs to attempt in one pass, so a backlog cannot turn one tick into an hour
     *                        of rail calls. The oldest attempts are taken first, so a long backlog drains
     *                        rather than starving
     * @return what was attempted, and what each attempt did or why it did nothing
     */
    Result<DrivenRuns> driveDue(Instant attemptedBefore, int batchSize);

    /**
     * Drives one named run, whatever the window - which is how an operator asks for it.
     *
     * <p>A named request is not a stampede, so the threshold does not apply: the caller has decided that this
     * run should be attempted now, and refusing on the grounds that it was attempted recently would make the
     * command useless in exactly the case it exists for (a run that was attempted and did not finish).
     *
     * @return the attempt's report, or {@link RunRefusals.NoSuchRun} when there is no such run
     */
    Result<DrivenRuns> driveOne(UUID runId);
}
