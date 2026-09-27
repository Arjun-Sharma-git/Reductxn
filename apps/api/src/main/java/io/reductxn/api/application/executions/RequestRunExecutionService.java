package io.reductxn.api.application.executions;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadRun;
import io.reductxn.api.port.in.RequestRunExecution;
import io.reductxn.api.port.in.RequestedExecution;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.RunExecutionRequests;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: record a member's request for a run to be offered to the rail now.
 *
 * <p><strong>It decides nothing about money, and that is deliberate.</strong> The transfer that moves anything is
 * {@code workers/settlement}'s {@code ExecuteRun}, which this module cannot call (RULE B6) - so all this does is put
 * the member's intent on the run's row, where the driver reads it. What it does decide is whether the request is worth
 * recording, and it decides it from what the worker wrote down rather than by deriving anything: a run's status and the
 * gates' conclusion in {@code settlement_run_gates}. Quorum is arithmetic this module may not perform (ADR 0021), and
 * {@link RunReport.Gates} exists so it does not have to.
 *
 * <p><strong>The order is the caller, then the run, then the request.</strong> Membership is asked before the run's
 * state is judged and the answer is the one code for "no such group" and "not yours" (ADR 0044, 0049) - a stranger
 * holding a run id must not be able to tell a run that is executing from one that does not exist.
 */
@Service
public class RequestRunExecutionService implements RequestRunExecution {

    private final ReadRun readRun;
    private final Groups groups;
    private final RunExecutionRequests requests;
    private final Clock clock;

    public RequestRunExecutionService(ReadRun readRun, Groups groups, RunExecutionRequests requests, Clock clock) {
        this.readRun = Objects.requireNonNull(readRun, "readRun");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Result<RequestedExecution> request(UUID callerId, UUID runId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(runId, "runId");

        Result<RunReport> found = readRun.read(runId);

        if (found instanceof Result.Failure<RunReport> refused) {
            return Result.fail(refused.error());
        }

        RunReport run = ((Result.Ok<RunReport>) found).value();

        if (!groups.isMember(run.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(run.groupId()));
        }

        if (!canStillMove(run)) {
            return Result.fail(new RunRefusals.NotReadyToExecute(runId, run.status()));
        }

        Instant requestedAt = clock.instant();
        requests.record(runId, callerId, requestedAt);

        // The clock's instant rather than the row's: the request is what was written at that moment, and reading it
        // back would be a second query for a fact this call just made true.
        return Result.ok(new RequestedExecution(runId, callerId, requestedAt));
    }

    /**
     * Whether asking could still change anything, decided from what the worker wrote down.
     *
     * <p><strong>The states that refuse are a list rather than the complement of a rule, and the difference is a run
     * from a newer worker.</strong> A status this build has not heard of is accepted: {@code RunReport} refuses to
     * refuse an unknown state on the grounds that a reader that recognised only the states it knew would break on
     * upgrade, and the same argument applies here with more force - the request is a row and the worker decides what to
     * do with it, so a {@code 409} from the wrong build would be a refusal the run itself never made.
     *
     * <p><strong>No verdict yet means no opinion.</strong> A run the worker has not looked at has no gates row, so its
     * quorum is unknowable from here - and the driver will reach a verdict of its own a pass later, which is the
     * moment that judgement belongs to. Refusing on the absence of a verdict would make this endpoint unusable for
     * exactly the runs a member is most likely to be looking at.
     */
    private static boolean canStillMove(RunReport run) {
        return switch (run.status()) {
            // Moving, finished, or still being opened. None of them can be influenced by asking.
            case "executing", "done", "failed", "rejected", "pending", "computing" -> false;

            // Waiting on its group: worth recording only when the approval it needs is already there. The verdict is
            // the worker's and this module only reads it.
            case "awaiting_approval" -> run.gates() == null || run.gates().quorum().met();

            // Waiting on currency, and anything a newer worker has learned: accepted, because the driver honours the
            // request the moment the obstacle clears.
            default -> true;
        };
    }
}
