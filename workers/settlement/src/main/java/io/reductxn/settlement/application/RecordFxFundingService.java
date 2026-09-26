package io.reductxn.settlement.application;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.RecordFxFunding;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Currency arriving from outside, and the transition it unblocks (RULE S4).
 *
 * <p><strong>This is the other side of the funding gate.</strong> Until it existed, {@code awaiting_fx_funding}
 * was a state a run could enter and never leave: the spec described a group being asked for currency and
 * nothing in the product could record that the ask was met. Now the ask has an answer, and the gate has
 * something to open.
 *
 * <p>Three refusals, in the order that produces the clearest answer:
 *
 * <ol>
 *   <li><strong>The run is waiting on the group at all.</strong> Funding an executed run is refused with the
 *       state, because there is nothing for the money to unblock.</li>
 *   <li><strong>This run requires this currency.</strong> A funding for a currency the plan does not need at all
 *       would be a row asserting that a gap was closed while naming a gap that was never open. Funding
 *       <em>more</em> than the requirement is a different thing and is recorded: the currency did arrive, and the
 *       report shows the surplus rather than hiding it.</li>
 *   <li><strong>The amount is a payment.</strong> Strictly positive; a correction is another row.</li>
 * </ol>
 *
 * <p><strong>It is idempotent on the reference.</strong> A caller that timed out does not know whether the
 * funding was recorded, so the reference keys the insert and a retry records nothing new. That is the same rule
 * as the run's rail references (S2) applied to the other thing in this module that can half-happen.
 */
@Service
public class RecordFxFundingService implements RecordFxFunding {

    private final Runs runs;
    private final Clock clock;

    public RecordFxFundingService(Runs runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public Result<ComputedRun> fund(FundingRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<ComputedRun> found = runs.findRunById(request.runId());

        if (found.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchRun(request.runId()));
        }

        ComputedRun run = found.get();
        UUID runId = request.runId();

        if (!run.run().status().isWaitingOnTheGroup()) {
            return Result.fail(new RunRefusals.NotWaitingOnTheGroup(runId, run.run().status()));
        }

        if (!run.funding().requiredCurrencies().contains(request.currency())) {
            return Result.fail(new RunRefusals.FundingNotRequired(runId, request.currency(),
                    run.funding().required()));
        }

        if (!request.amount().isPositive()) {
            return Result.fail(new RunRefusals.FundingNotPositive(runId, request.amount()));
        }

        runs.recordFunding(request, clock.instant());
        ComputedRun updated = readBack(runId);

        // Only a run that was blocked on funding moves, and only once every currency it needs is covered. A
        // partial funding leaves the run where it was and the report says how much is still outstanding, which is
        // the honest answer: the group has brought some of the currency in and the run is still blocked.
        if (updated.run().status() == RunStatus.AWAITING_FX_FUNDING && updated.funding().satisfied()) {
            runs.updateStatus(updated.run().withStatus(RunStatus.AWAITING_APPROVAL));
            return Result.ok(readBack(runId));
        }

        return Result.ok(updated);
    }

    /** The run as stored, with the funding just written included. */
    private ComputedRun readBack(UUID runId) {
        return runs.findRunById(runId).orElseThrow(() -> new IllegalStateException(
                "run " + runId + " was read, written and cannot be read back. Every statement here is in one "
                        + "transaction, so this means the rows went somewhere else"));
    }
}
