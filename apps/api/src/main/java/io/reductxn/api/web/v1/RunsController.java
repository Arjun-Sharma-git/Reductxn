package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadRun;
import io.reductxn.api.port.in.RecordDecision;
import io.reductxn.api.port.in.NewDecision;
import io.reductxn.api.port.in.RecordedDecision;
import io.reductxn.api.port.in.RequestRunExecution;
import io.reductxn.api.port.in.RequestedExecution;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.DecisionMapper;
import io.reductxn.api.web.dto.RequestedExecutionResponse;
import io.reductxn.api.web.dto.RunMapper;
import io.reductxn.api.web.dto.DecisionRequest;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code POST /v1/runs/{runId}/decisions}, {@code GET /v1/runs/{runId}} and {@code GET /v1/runs/{runId}/transfers},
 * per the contract.
 *
 * <p><strong>The first endpoints in this module that are about something it does not own.</strong> The run, its
 * partitions and its decision rows belong to {@code workers/settlement}; this records a member's answer into them and
 * projects them back, because a member's surface is here and the two deployables do not depend on each other (RULE
 * B6). What that costs and what it buys is ADR 0020's subject for the write and ADR 0021's for the read. What it
 * means for this class is narrower: it parses, it reads the clock, it hands the use case a command, and it turns
 * refusals into the contract's problem bodies - it computes no quorum, checks no lifecycle and derives no figure.
 *
 * <p><strong>The path id is taken as a {@code String} and parsed here rather than bound as a {@code UUID}.</strong>
 * The declaration would be shorter and would answer a malformed id with Spring's own {@code 400}, whose body carries
 * no code - the same compromise {@code ObligationsController} refuses for a missing header. A client that cannot
 * branch on an error body is a client that has to parse prose.
 */
@RestController
@RequestMapping("/v1/runs")
public class RunsController {

    private final RecordDecision recordDecision;
    private final ReadRun readRun;
    private final RequestRunExecution requestRunExecution;
    private final CallerResolver callers;
    private final Clock clock;

    public RunsController(RecordDecision recordDecision, ReadRun readRun, RequestRunExecution requestRunExecution,
            CallerResolver callers, Clock clock) {
        this.recordDecision = Objects.requireNonNull(recordDecision, "recordDecision");
        this.readRun = Objects.requireNonNull(readRun, "readRun");
        this.requestRunExecution = Objects.requireNonNull(requestRunExecution, "requestRunExecution");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@code POST /v1/runs/{runId}/decisions}: a member's answer, yes or no.
     *
     * <p>The operation is named for the answer rather than for one of them, and that is a correction the second
     * answer forced: a path called {@code /approve} that records refusals would be the kind of near-miss this
     * module's read side already refuses to publish (ADR 0021). The request body keeps its old meaning - absent
     * {@code decision} is an approval - so the change is in the path and the response, not in what a client sends.
     *
     * <p><strong>The caller is resolved first, and since ADR 0053 the caller <em>is</em> the answering member.</strong>
     * This was the last operation whose acting member arrived in the body; the order is now the one {@code execute}
     * uses, and for its reason - who is asking is settled before the path is judged, so a request with no session is
     * refused as a {@code 401} rather than as a malformed id. The body no longer carries an identity at all, so
     * there is nothing left for this method to check in it: the member's id was the only field that needed one.
     */
    @PostMapping("/{runId}/decisions")
    public ResponseEntity<Object> decide(
            @PathVariable String runId,
            @RequestBody(required = false) DecisionRequest request,
            HttpServletRequest http) {

        // Who is asking first: a request with no session is refused as such, and an answer by nobody is no longer
        // expressible - which is what the `memberId` field used to express and could not enforce.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedRunId(runId);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        NewDecision command = DecisionMapper.toCommand(UUID.fromString(runId), request, clock.instant());
        Result<RecordedDecision> result = recordDecision.record(
                ((Result.Ok<SignedInUser>) caller).value().id(), command);

        if (result instanceof Result.Ok<RecordedDecision> recorded) {
            // 200 rather than 201: nothing was created that a client can fetch, and the contract says "Decision
            // recorded" for this status. The body echoes what the row says, so a client that recorded an answer
            // has something to show a member - including which answer it was, and whose.
            return ResponseEntity.ok(DecisionMapper.toResponse(recorded.value()));
        }

        return problem(((Result.Failure<RecordedDecision>) result).error(), http.getRequestURI());
    }

    /**
     * {@code POST /v1/runs/{runId}/execute}: a member asks for this run to be offered to the rail now.
     *
     * <p><strong>The endpoint that makes the settlement worker's last unreachable use case reachable.</strong>
     * {@code ExecuteRun} exists, is tested, and had no production caller until a schedule took it up - and the same
     * fact was true one level down of {@code DriveRuns.driveOne}, the "drive this named run, ignoring the window"
     * primitive, which only tests call. This is the member-facing door onto it: the request is recorded on the run's
     * own row, and the worker's next pass honours it (ADR 0050).
     *
     * <p><strong>What it answers, and what it refuses.</strong> {@code 202} with a receipt, because nothing has moved
     * yet. {@code 404} for a run that does not exist and for one whose group the caller is not in - one answer, so a
     * guessed id cannot be tested. {@code 409} for a run that cannot move: already executing, finished, still being
     * opened, or waiting on a group that has not approved enough of it. Every judgement about the run is the use
     * case's; this method resolves the caller, parses the path and turns the answer into the contract's shape.
     */
    @PostMapping("/{runId}/execute")
    public ResponseEntity<Object> execute(@PathVariable String runId, HttpServletRequest http) {
        // Who is asking first: a request with no session is refused as such rather than as a malformed path.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedRunId(runId);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<RequestedExecution> requested = requestRunExecution.request(
                ((Result.Ok<SignedInUser>) caller).value().id(), UUID.fromString(runId));

        if (requested instanceof Result.Ok<RequestedExecution> recorded) {
            // 202 rather than 201: the request is recorded, and the thing a client would want to fetch next is the
            // run - which already exists and is where the money's own answer will appear.
            return ResponseEntity.accepted().body(RequestedExecutionResponse.of(recorded.value()));
        }

        return problem(((Result.Failure<RequestedExecution>) requested).error(), http.getRequestURI());
    }

    /**
     * {@code GET /v1/runs/{runId}}: the run, its figures per currency and who has answered.
     *
     * <p>This is the other half of approving, and the contract's own word for the approve operation shows why it is
     * needed: {@code 200 Approval recorded} is true and incomplete, because whether an answer reached the run's
     * quorum is a judgement this deployable does not make (ADR 0020). The answers appear here as rows, and the run's
     * {@code status} is the state machine's answer to what is happening next.
     */
    @GetMapping("/{runId}")
    public ResponseEntity<Object> read(@PathVariable String runId, HttpServletRequest http) {
        DomainError malformed = malformedRunId(runId);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<RunReport> result = readRun.read(UUID.fromString(runId));

        if (result instanceof Result.Ok<RunReport> run) {
            return ResponseEntity.ok(RunMapper.toResponse(run.value()));
        }

        return problem(((Result.Failure<RunReport>) result).error(), http.getRequestURI());
    }

    /**
     * {@code GET /v1/runs/{runId}/transfers}: the who-pays-whom instruction list.
     *
     * <p>Served from the same read as the run above, so the two endpoints cannot disagree about a plan that a group
     * approved and a rail is executing.
     */
    @GetMapping("/{runId}/transfers")
    public ResponseEntity<Object> transfers(@PathVariable String runId, HttpServletRequest http) {
        DomainError malformed = malformedRunId(runId);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<RunReport> result = readRun.read(UUID.fromString(runId));

        if (result instanceof Result.Ok<RunReport> run) {
            return ResponseEntity.ok(RunMapper.toInstructions(run.value()));
        }

        return problem(((Result.Failure<RunReport>) result).error(), http.getRequestURI());
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        ProblemDetail problem = ExpectedFailures.toProblem(failure, instance);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * The path parameter, which the contract declares as a uuid.
     *
     * <p><strong>Everything this operation used to check about its body is gone with the field that needed it</strong>
     * (ADR 0053): the member is the caller now, so the only malformation left on this endpoint is a path that is not
     * an id. The check stayed here rather than being replaced by a {@code UUID} parameter, for the reason it was here
     * to begin with.
     *
     * <p>Taken as a {@code String} and checked here rather than bound as a {@code UUID}, so that a malformed id gets
     * the same coded problem body every other refusal gets instead of Spring's own uncoded {@code 400} - the
     * compromise {@code ObligationsController} refuses for a missing header, for the same reason.
     */
    private static DomainError malformedRunId(String runId) {
        if (runId == null || runId.isBlank() || !isUuid(runId)) {
            return new RunRefusals.RequestIncomplete("runId in the path, as a uuid");
        }

        return null;
    }

    private static boolean isUuid(String candidate) {
        try {
            UUID.fromString(candidate);
            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }
}
