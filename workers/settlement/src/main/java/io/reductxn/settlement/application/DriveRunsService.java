package io.reductxn.settlement.application;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Makes the runs that can move, move.
 *
 * <p>This is a thin service, and the thinness is the design. Everything a run needs to progress is already a use
 * case with its gates in it - quorum, funding, rejection, rail support - so the driver's whole job is to decide
 * <em>which</em> runs to offer to {@link ExecuteRun} and to record that it did. A driver with rules of its own
 * would be a second place where "may this run execute?" is answered, and the two would eventually disagree about
 * money.
 *
 * <p><strong>It is deliberately not {@code @Transactional}.</strong> {@link ExecuteRun} is not either, for the
 * reason section 4.2 gives - the rail is outside the database, and a transaction held across a call to it is a
 * transaction that has to be rolled back after the money moved. The one write this service makes is the mark
 * that a run was attempted, and that goes in its own transaction, before the attempt, exactly as a transfer's
 * authorisation does. A driver wrapped in a transaction would be the most expensive mistake available here: one
 * transaction spanning every rail call in a pass.
 *
 * <p><strong>A refusal is a result, not a failure.</strong> Most of what a pass finds is a run waiting on a
 * group's approval or on currency arriving, and {@link ExecuteRun} answers those with a refusal whose code says
 * which gate is waiting. The driver reports them rather than raising, so a cadence that finds nothing to do is
 * quiet and a cadence that finds a problem says which one.
 */
public class DriveRunsService implements DriveRuns {

    /** What a pass may attempt when the caller has not said, so no caller can forget to bound it. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    private final Runs runs;
    private final ExecuteRun executeRun;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public DriveRunsService(
            Runs runs,
            ExecuteRun executeRun,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        this.runs = Objects.requireNonNull(runs, "runs");
        this.executeRun = Objects.requireNonNull(executeRun, "executeRun");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Result<DrivenRuns> driveDue(Instant attemptedBefore, int batchSize) {
        Objects.requireNonNull(attemptedBefore, "attemptedBefore");

        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "a pass drives at least one run or does nothing at all, but the batch size was "
                            + batchSize + ". A driver told to consider nothing would look like a worker that is "
                            + "broken rather than one that is idle");
        }

        List<SettlementRun> due = runs.dueForDriving(attemptedBefore, batchSize);

        return Result.ok(drive(due, attemptedBefore));
    }

    @Override
    public Result<DrivenRuns> driveOne(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        Optional<ComputedRun> found = runs.findRunById(runId);

        if (found.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchRun(runId));
        }

        // The run as it stands, from the report the caller answered with, rather than a second query for a fact
        // already in hand. `attemptedBefore` is the caller's instant: a named run is driven regardless of the
        // window, so what the report records is when this attempt was asked for.
        return Result.ok(drive(List.of(found.get().run()), clock.instant()));
    }

    /**
     * Attempts each run in turn, collecting what happened.
     *
     * <p>Sequential rather than concurrent, and that is a decision. A pass is bounded by its batch size and its
     * window, so the rate is set by the cadence; running a batch in parallel would multiply the rail calls one
     * pass makes at the exact moment a rail is most likely to be unhappy, and the ordering that prevents
     * starvation only means something if the pass works through the queue in order.
     */
    private DrivenRuns drive(List<SettlementRun> due, Instant attemptedBefore) {
        List<DrivenRuns.RunOutcome> outcomes = new ArrayList<>(due.size());

        for (SettlementRun run : due) {
            outcomes.add(drive(run));
        }

        return new DrivenRuns(attemptedBefore, outcomes);
    }

    /**
     * One run: mark the attempt, then let the gates decide.
     *
     * <p>The mark goes first and in its own transaction. After it, everything that happens is
     * {@link ExecuteRun}'s - including the transfers it writes one by one, each committed before the call it
     * describes.
     *
     * <p>The refusal branch reports the run's status <em>as it was when the pass looked</em>, which is the honest
     * answer: a refusal changes nothing, so the status a reader would find now is the one the driver saw. It is
     * not re-read, and that is deliberate - a refusal is not worth a second query, and the status of a run that
     * another process moved in the meantime is that process's answer to give rather than this one's.
     */
    private DrivenRuns.RunOutcome drive(SettlementRun run) {
        inTransaction(() -> runs.markDriven(run.id(), clock.instant()));

        Result<ComputedRun> attempted = executeRun.execute(run.id());

        if (attempted instanceof Result.Failure<ComputedRun> refusal) {
            return new DrivenRuns.RunOutcome(
                    run.id(), run.groupId(), run.status(),
                    refusal.error().code() + ": " + refusal.error().message());
        }

        return new DrivenRuns.RunOutcome(
                run.id(), run.groupId(), attempted.orElseThrow().run().status(), null);
    }

    private void inTransaction(Runnable write) {
        transactions.executeWithoutResult(state -> write.run());
    }
}
