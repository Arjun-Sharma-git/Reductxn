package io.reductxn.settlement.application;

import io.reductxn.booking.api.Bookings;
import io.reductxn.booking.api.SettledTransfer;
import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.ResolveTransfer;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.TransferResolution;
import io.reductxn.settlement.port.in.TransferStatus;
import io.reductxn.settlement.port.out.ChainHeads;
import io.reductxn.settlement.port.out.JournalEntries;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Recording what a person found out about a transfer no rail would decide (RULE S6).
 *
 * <p><strong>Why a use case and not a writer.</strong> A resolution is not one row: claiming the money moved means
 * the transfer is confirmed <em>and</em> the movement is booked, and the two have to happen together or neither -
 * a confirmation without an entry is money that moved and was never recorded, which is the one thing the ledger
 * exists to prevent (section 4a). It is also not only about the transfer: the answer can complete the run or end
 * it, and the run's fate has to be written by the same rule execution uses. Both of those are decisions, and a
 * decision belongs to a use case rather than to an adapter (RULE B15).
 *
 * <p><strong>Nothing here asks the rail anything, and no other transfer is touched.</strong> The operator already
 * has the answer - that is what the escalation report is for (ADR 0024) - so this command makes no network call at
 * all. It follows from that the pass does not offer the run's other transfers to the rail either: an operator
 * resolving one movement has not asked for the run to be attempted, and a command that quietly did that would move
 * money nobody asked it to move. The run's gates are not re-read for the same reason - a resolution is an answer
 * about one movement, not a new attempt at the run.
 *
 * <p><strong>The booking is the confirmation path, not a second one.</strong> The entry is
 * {@code Bookings.settlement} against the transfer's own coordinates and the chain head this adapter's sibling
 * read under its lock, exactly as {@code ExecuteRunService} books a rail's confirmation - and its id is derived
 * from the transfer, so even a resolution that somehow ran twice would book one movement (RULE S2). What is
 * different is only where the answer came from.
 *
 * <p><strong>The write clears the silence, and that is not tidiness.</strong> A transfer with an answer is not
 * waiting for anything and is not silent: {@code unanswered_since} is what the escalation report is a query over,
 * so a resolved transfer that kept its silence would keep appearing in a report telling people to look at something
 * that has been decided. It is the write {@code markTransfer} makes for a rail's answer, and the transfer's own
 * invariants refuse the combination outright.
 *
 * <p><strong>Three refusals, and each is a case where the assertion cannot be true of the row.</strong> See
 * {@link RunRefusals}: a transfer already resolved by anybody (naming who, so a retry after a timeout recognises
 * itself), a transfer no rail was ever asked about, and a transfer that is not in the run the caller named. What is
 * deliberately *not* refused is a transfer that is not yet past the escalation policy: patience is the worker's
 * prompt to a human, not a permission a human needs.
 */
public class ResolveTransferService implements ResolveTransfer {

    private final Runs runs;
    private final JournalEntries journalEntries;
    private final ChainHeads chainHeads;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ResolveTransferService(
            Runs runs,
            JournalEntries journalEntries,
            ChainHeads chainHeads,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        this.runs = Objects.requireNonNull(runs, "runs");
        this.journalEntries = Objects.requireNonNull(journalEntries, "journalEntries");
        this.chainHeads = Objects.requireNonNull(chainHeads, "chainHeads");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public Result<ComputedRun> resolve(TransferResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");

        UUID runId = resolution.runId();
        Optional<ComputedRun> found = runs.findRunById(runId);

        if (found.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchRun(runId));
        }

        ComputedRun run = found.get();
        Optional<PlannedTransfer> named = run.transfers().stream()
                .filter(transfer -> transfer.id().equals(resolution.transferId()))
                .findFirst();

        if (named.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchTransfer(runId, resolution.transferId()));
        }

        PlannedTransfer transfer = named.get();

        // An answer that has arrived is a fact about money. A second one - even an identical one, even from the same
        // person - would rewrite it, so the refusal names what it is protecting rather than saying "already done": an
        // operator re-running this after a slow response is the case that meets it, and they need to see that the
        // write they are unsure about is the one already stored.
        if (transfer.status().isResolved()) {
            return Result.fail(new RunRefusals.TransferAlreadyResolved(runId, transfer.id(), transfer.status(),
                    transfer.resolvedBy(), transfer.resolvedAt(), transfer.resolvedBasis()));
        }

        // Nothing was ever offered to a rail, so there is no rail's answer to have found out. That covers a run
        // rejected before execution as well as a worker that died between writing its plan and its first call - and
        // in both cases, if money moved, the missing fact is a submission rather than a decision.
        if (!transfer.status().isOffered()) {
            return Result.fail(new RunRefusals.TransferNotOffered(runId, transfer.id(), transfer.status()));
        }

        TransferStatus status = resolution.outcome().transferStatus();
        Instant now = clock.instant();

        // One write plus at most one booking, in one transaction: a confirmation with no entry, or an entry with no
        // confirmation, is a state nobody could reconstruct later. Execution writes one transfer at a time because it
        // must not hold a transaction across a network call, and this pass never makes one.
        inTransaction(() -> {
            if (status == TransferStatus.CONFIRMED) {
                journalEntries.append(Bookings.settlement(
                        chainHeads.forGroup(run.run().groupId()),
                        new SettledTransfer(transfer.id(), run.run().groupId(), transfer.fromUserId(),
                                transfer.toUserId(), transfer.amount(), now, transfer.railRef())));
            }

            runs.resolveTransfer(transfer.id(), status, now, resolution.by(), resolution.basis());
        });

        ComputedRun after = readBack(runId);
        RunStatus outcome = RunOutcome.of(after);

        if (outcome == after.run().status()) {
            return Result.ok(after);
        }

        // The run's fate follows from its transfers, by the rule execution uses too (RunOutcome): an answer saying the
        // money did not move ends the run as failed, and answers that confirm everything complete it. A run whose
        // status is already right is not written - a status write that changes nothing is still a row lock.
        inTransaction(() -> runs.updateStatus(after.run().withStatus(outcome)));

        return Result.ok(readBack(runId));
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
     * <p>Programmatic rather than an annotation on this class, for the reason {@code ExecuteRunService} gives a
     * longer version of: this pass writes a transfer and books a movement together, and a rollback after a booking
     * would erase the record that money moved.
     */
    private void inTransaction(Runnable work) {
        transactions.executeWithoutResult(state -> work.run());
    }
}
