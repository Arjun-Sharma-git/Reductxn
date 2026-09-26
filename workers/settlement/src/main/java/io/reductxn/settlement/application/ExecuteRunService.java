package io.reductxn.settlement.application;

import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.SettledTransfer;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.in.TransferStatus;
import io.reductxn.settlement.port.out.ChainHeads;
import io.reductxn.settlement.port.out.JournalEntries;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Submitting a run's transfers to a rail, and recording what the rail answers.
 *
 * <p><strong>The gates come first, all of them, before anything moves.</strong> Quorum (RULE S1), the funding
 * requirement (RULE S4) and the rail's declared currencies are checked against the run's stored rows. Each has
 * its own refusal, and a run that fails any of them is answered with its transfers untouched. The alternative -
 * discovering that a currency cannot be settled half-way through a submission loop - would turn a deployment
 * mistake into the partial application RULE S3 makes a first-class outcome for reasons outside anyone's control.
 *
 * <p><strong>There is no {@code @Transactional} on {@link #execute}, and that is a decision with a
 * reason.</strong> The rail is outside the database, so one transaction around the whole pass would hold a
 * connection open across every network call, and - worse - a rollback after a transfer confirmed would erase the
 * record that money moved. Section 4.2 of the spec says every transition is persisted <em>before</em> the next
 * call is made, and that is a statement about crash recovery: a worker that dies mid-run has to leave a readable
 * trail of what it had done. So each write is committed on its own through {@link TransactionTemplate}, and the
 * exception is narrow: a single write, never a write plus a call to the outside.
 *
 * <p><strong>Execution resumes rather than restarting.</strong> A second call skips what is resolved, asks the
 * rail about what it already has, and offers the rail only what it has never accepted. That makes RULE S2's
 * idempotency structural instead of a flag: there is no "already executed" check to get wrong, because the
 * transfer's own state and the rail's own memory decide what happens.
 *
 * <p><strong>A partial failure is not undone.</strong> If some transfers confirm and one fails terminally the run
 * is {@code failed}: the confirmed ones stay confirmed, the failed one keeps the rail's reason, and a later pass
 * can still resolve anything left in flight - while the run stays failed, because the failure is a fact about
 * money that did not move and a group has to see it (RULE S3).
 */
@Service
public class ExecuteRunService implements ExecuteRun {

    private final Runs runs;
    private final PaymentRail rail;
    private final JournalEntries journalEntries;
    private final ChainHeads chainHeads;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ExecuteRunService(
            Runs runs,
            PaymentRail rail,
            JournalEntries journalEntries,
            ChainHeads chainHeads,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        this.runs = Objects.requireNonNull(runs, "runs");
        this.rail = Objects.requireNonNull(rail, "rail");
        this.journalEntries = Objects.requireNonNull(journalEntries, "journalEntries");
        this.chainHeads = Objects.requireNonNull(chainHeads, "chainHeads");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Result<ComputedRun> execute(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        Optional<ComputedRun> found = runs.findRunById(runId);

        if (found.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchRun(runId));
        }

        ComputedRun run = found.get();

        // RULE S2's idempotency at the top: executing a run that has finished is a read of it. Not an error - a
        // caller that retried after a timeout is the ordinary case this is for - and not a second movement.
        if (run.run().status() == RunStatus.DONE) {
            return Result.ok(run);
        }

        Result<ComputedRun> refusal = gates(run);

        if (refusal != null) {
            return refusal;
        }

        inTransaction(() -> runs.updateStatus(run.run().withStatus(RunStatus.EXECUTING)));

        for (PlannedTransfer transfer : run.transfers()) {
            if (transfer.status().isResolved()) {
                continue;
            }

            if (transfer.status().isAtTheRail()) {
                askTheRailForIt(transfer, run.run());
            } else {
                giveItToTheRail(transfer, run.run());
            }
        }

        ComputedRun after = readBack(runId);
        RunStatus outcome = outcomeOf(after);

        if (outcome == after.run().status()) {
            return Result.ok(after);
        }

        inTransaction(() -> runs.updateStatus(after.run().withStatus(outcome)));

        return Result.ok(readBack(runId));
    }

    /**
     * Every gate, in the order that produces the most useful refusal, or null when the run may proceed.
     *
     * <p>The rejection check is here as well as in the run's status, and that duplication is deliberate: the
     * status is what a reader sees, and the decisions are what actually happened. A crash between recording a
     * rejection and writing the status would leave a rejected run that looked approvable, and the only thing
     * standing between that and a settlement nobody agreed to is this check.
     */
    private Result<ComputedRun> gates(ComputedRun run) {
        UUID runId = run.run().id();

        if (run.quorum().rejected() || run.run().status() == RunStatus.REJECTED) {
            return Result.fail(new RunRefusals.RunRejected(runId,
                    run.quorum().rejecters().stream().findFirst().orElse(null)));
        }

        if (!run.quorum().met()) {
            return Result.fail(new RunRefusals.QuorumNotMet(runId, run.quorum()));
        }

        if (!run.funding().satisfied()) {
            return Result.fail(new RunRefusals.FxUnfunded(runId, run.funding().outstanding()));
        }

        List<Currency> unsupported = run.transfers().stream()
                .map(PlannedTransfer::currency)
                .distinct()
                .filter(currency -> !rail.supportedCurrencies().contains(currency))
                .toList();

        if (!unsupported.isEmpty()) {
            return Result.fail(new RunRefusals.RailCannotSettle(runId, unsupported));
        }

        return null;
    }

    /**
     * Offers one transfer to the rail, recording that we are about to before we do.
     *
     * <p>The authorisation is written first, in its own transaction, and the reason is the crash in between: a
     * worker that dies after the rail accepted a transfer but before the answer was stored leaves a transfer in
     * {@code authorized}, which the next pass offers again - and RULE S2 makes that a no-op rather than a second
     * movement. Had the write been saved for after the call, the same crash would leave no trace that the
     * transfer had ever been sent, and nothing would know to ask the rail about it.
     */
    private void giveItToTheRail(PlannedTransfer transfer, SettlementRun run) {
        inTransaction(() -> runs.markTransfer(transfer.id(), TransferStatus.AUTHORIZED, null, clock.instant()));

        Result<PaymentRail.RailReference> answer = rail.submit(new PaymentRail.TransferInstruction(
                run.id(), transfer.railRef(), transfer.fromUserId(), transfer.toUserId(), transfer.amount()));

        if (answer instanceof Result.Failure<PaymentRail.RailReference> failure) {
            // The call did not complete: the rail never took the transfer. It stays `authorized` rather than
            // `failed`, because nothing was refused and nothing moved - a later pass offers it again, which is
            // what a retryable failure means. The reason is recorded so the next caller is not guessing.
            String reason = failure.error().code() + ": " + failure.error().message();
            inTransaction(() ->
                    runs.markTransfer(transfer.id(), TransferStatus.AUTHORIZED, reason, clock.instant()));
            return;
        }

        record(transfer, run, answer.orElseThrow().status());
    }

    /**
     * Asks the rail where a transfer it already has has got to.
     *
     * <p>Asked rather than re-submitted. Re-submitting would be safe - the rail is idempotent on the reference -
     * but the question a transfer in {@code submitted} poses is whether the money moved, and the rail's answer is
     * the only thing that knows. A rail that cannot be reached leaves the transfer where it is: silence is not a
     * failure state, and the sweep in section 4.4 is what will decide when patience runs out.
     */
    private void askTheRailForIt(PlannedTransfer transfer, SettlementRun run) {
        Result<PaymentRail.RailStatus> answer = rail.status(transfer.railRef());

        if (answer instanceof Result.Failure<PaymentRail.RailStatus> failure) {
            String reason = failure.error().code() + ": " + failure.error().message();
            inTransaction(() -> runs.markTransfer(transfer.id(), TransferStatus.SUBMITTED, reason,
                    clock.instant()));
            return;
        }

        record(transfer, run, answer.orElseThrow());
    }

    /**
     * Writes what the rail answered, and - when the answer is a confirmation - the entry that records it.
     *
     * <p><strong>The status and the entry commit together, and that is the reason this method exists.</strong>
     * A confirmation is the one moment at which a transfer's state and the ledger have to agree: a run whose
     * transfer reads {@code confirmed} with no entry behind it would report money moved that the ledger never
     * saw, and an entry with a transfer still reading {@code submitted} would be a movement that no retry
     * would ever repeat or reconcile. Two writes, one transaction, one instant.
     *
     * <p>The chain head is read inside that transaction rather than before it, because the lock that makes the
     * read safe is held until the transaction ends ({@link io.reductxn.settlement.port.out.ChainHeads}). This
     * is also why the instant is taken once and used for both the entry and the row: the movement happened at
     * one moment, and the ledger's copy of it should not be a microsecond later than the run's.
     */
    private void record(PlannedTransfer transfer, SettlementRun run, PaymentRail.RailStatus answer) {
        TransferStatus status = stateOf(answer.state());
        Instant now = clock.instant();

        inTransaction(() -> {
            if (status == TransferStatus.CONFIRMED) {
                journalEntries.append(Bookings.settlement(
                        chainHeads.forGroup(run.groupId()),
                        new SettledTransfer(transfer.id(), run.groupId(), transfer.fromUserId(),
                                transfer.toUserId(), transfer.amount(), now, transfer.railRef())));
            }

            runs.markTransfer(transfer.id(), status, answer.detail(), now);
        });
    }

    /**
     * What the rail's answer means for the transfer.
     *
     * <p>{@code accepted} and {@code pending} both mean the rail has it and has not confirmed it, which is
     * {@code submitted} - the state name is about the rail's answer, not about optimism. An exhaustive switch, so
     * a state added to the port breaks the build here rather than being read as a submission.
     */
    private static TransferStatus stateOf(PaymentRail.RailStatus.State state) {
        return switch (state) {
            case CONFIRMED -> TransferStatus.CONFIRMED;
            case FAILED -> TransferStatus.FAILED;
            case ACCEPTED, PENDING -> TransferStatus.SUBMITTED;
        };
    }

    /**
     * Where the run stands, given every transfer's state.
     *
     * <p><strong>A run with nothing to move is done</strong>, and that is not a special case bolted on: a cycle
     * whose obligations cancel out produces no transfers, and refusing to finish it would leave a group unable to
     * close a cycle in which nothing was owed. {@code allMatch} on an empty stream is true, so this falls out of
     * the arithmetic rather than being written twice.
     *
     * <p>A terminal failure makes the run {@code failed} even if other transfers are still in flight, because the
     * run's report has to say that the settlement did not happen (S3). The in-flight ones are still polled by a
     * later pass, and the run stays failed when they resolve.
     */
    private static RunStatus outcomeOf(ComputedRun run) {
        if (run.transfers().stream().allMatch(transfer -> transfer.status() == TransferStatus.CONFIRMED)) {
            return RunStatus.DONE;
        }

        if (run.transfers().stream().anyMatch(transfer -> transfer.status() == TransferStatus.FAILED)) {
            return RunStatus.FAILED;
        }

        return RunStatus.EXECUTING;
    }

    /** The run as stored, with the write just made included. */
    private ComputedRun readBack(UUID runId) {
        return runs.findRunById(runId).orElseThrow(() -> new IllegalStateException(
                "run " + runId + " was read, written and cannot be read back. The row cannot delete itself, so "
                        + "this means the id changed or the rows went somewhere else"));
    }

    /**
     * One write, one transaction.
     *
     * <p>Programmatic rather than an annotation on the method, because this use case is deliberately not one
     * transaction: the rail calls have to happen outside it. The boundary is still in the application layer,
     * which is what RULE B15 asks - a commit in an adapter would be a commit nobody reviewed.
     */
    private void inTransaction(Runnable work) {
        transactions.executeWithoutResult(state -> work.run());
    }
}
