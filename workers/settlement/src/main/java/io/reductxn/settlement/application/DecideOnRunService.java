package io.reductxn.settlement.application;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DecideOnRun;
import io.reductxn.settlement.port.in.Quorum;
import io.reductxn.settlement.port.in.RunDecision;
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
 * A member's answer on a run, and what it does to the run.
 *
 * <p>Four checks in a deliberate order, and the order is the argument:
 *
 * <ol>
 *   <li><strong>The run exists.</strong> Everything below needs it.</li>
 *   <li><strong>It is still waiting on the group.</strong> Approving a run that has executed or been rejected is
 *       not an error the caller can fix by trying again, so it is refused with the state it is actually in.</li>
 *   <li><strong>The answerer is a participant.</strong> Checked against the run's own plan, because a quorum any
 *       member id could satisfy is not a quorum - see {@link RunRefusals.NotAParticipant}.</li>
 *   <li><strong>They have not answered already.</strong> A decision is final, and the primary key behind this
 *       check is what makes that true for two callers at once rather than only for one.</li>
 * </ol>
 *
 * <p><strong>A rejection ends the run; an approval only counts.</strong> No status changes on an approval, not
 * even when the last one makes quorum true. The run enters {@code executing} when execution starts and not
 * before, so a group that has approved everything is still looking at a run that has moved nothing.
 */
@Service
public class DecideOnRunService implements DecideOnRun {

    private final Runs runs;
    private final Clock clock;

    public DecideOnRunService(Runs runs, Clock clock) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    @Transactional
    public Result<ComputedRun> decide(RunDecision decision) {
        Objects.requireNonNull(decision, "decision");

        Optional<ComputedRun> found = runs.findRunById(decision.runId());

        if (found.isEmpty()) {
            return Result.fail(new RunRefusals.NoSuchRun(decision.runId()));
        }

        ComputedRun run = found.get();
        UUID runId = decision.runId();
        UUID memberId = decision.memberId();

        if (!run.run().status().isWaitingOnTheGroup()) {
            return Result.fail(new RunRefusals.NotWaitingOnTheGroup(runId, run.run().status()));
        }

        Quorum quorum = run.quorum();

        if (!quorum.members().contains(memberId)) {
            return Result.fail(new RunRefusals.NotAParticipant(runId, memberId));
        }

        if (quorum.decided().contains(memberId)) {
            return Result.fail(new RunRefusals.AlreadyDecided(runId, memberId,
                    quorum.approvers().contains(memberId)
                            ? RunDecision.Decision.APPROVE
                            : RunDecision.Decision.REJECT));
        }

        runs.recordDecision(decision, clock.instant());

        ComputedRun updated = readBack(runId);

        if (!decision.isRejection()) {
            return Result.ok(updated);
        }

        // The rejection is recorded first and the status second, in that order. A crash between them leaves a
        // rejected decision on a run that is still waiting - which execution also refuses, because it checks the
        // decisions as well as the status (see ExecuteRunService). Checking twice is deliberate: the status is the
        // readable answer and the decision is the fact, and a run must not be able to execute because a status
        // write was lost.
        runs.updateStatus(updated.run().withStatus(RunStatus.REJECTED));

        return Result.ok(readBack(runId));
    }

    /** The run as stored, with the decision just written included. Written together, so it is never absent. */
    private ComputedRun readBack(UUID runId) {
        return runs.findRunById(runId).orElseThrow(() -> new IllegalStateException(
                "run " + runId + " was read, written and cannot be read back. Every statement here is in one "
                        + "transaction, so this means the rows went somewhere else"));
    }
}
