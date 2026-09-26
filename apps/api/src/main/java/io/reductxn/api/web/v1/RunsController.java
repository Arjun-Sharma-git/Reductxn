package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ApprovalRefusals;
import io.reductxn.api.port.in.ApproveRun;
import io.reductxn.api.port.in.NewApproval;
import io.reductxn.api.port.in.RecordedApproval;
import io.reductxn.api.web.dto.ApprovalMapper;
import io.reductxn.api.web.dto.ApproveRunRequest;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code POST /v1/runs/{runId}/approve}, per the contract.
 *
 * <p><strong>The first endpoint in this module that is about something it does not own.</strong> The run, its
 * partitions and its decision rows belong to {@code workers/settlement}; this records a member's answer into them,
 * because a member's surface is here and the two deployables do not depend on each other (RULE B6). What that costs
 * and what it buys is ADR 0020's subject. What it means for this class is narrower: it parses, it reads the clock,
 * it hands the use case a command, and it turns refusals into the contract's problem bodies - it computes no
 * quorum and checks no lifecycle.
 *
 * <p><strong>The path id is taken as a {@code String} and parsed here rather than bound as a {@code UUID}.</strong>
 * The declaration would be shorter and would answer a malformed id with Spring's own {@code 400}, whose body carries
 * no code - the same compromise {@code ObligationsController} refuses for a missing header. A client that cannot
 * branch on an error body is a client that has to parse prose.
 */
@RestController
@RequestMapping("/v1/runs")
public class RunsController {

    private final ApproveRun approveRun;
    private final Clock clock;

    public RunsController(ApproveRun approveRun, Clock clock) {
        this.approveRun = Objects.requireNonNull(approveRun, "approveRun");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<Object> approve(
            @PathVariable String runId,
            @RequestBody(required = false) ApproveRunRequest request,
            HttpServletRequest http) {

        DomainError malformed = malformedRequest(runId, request);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        NewApproval command = ApprovalMapper.toCommand(UUID.fromString(runId), request, clock.instant());
        Result<RecordedApproval> result = approveRun.approve(command);

        if (result instanceof Result.Ok<RecordedApproval> recorded) {
            // 200 rather than 201: nothing was created that a client can fetch, and the contract says "Approval
            // recorded" for this status. The body echoes what the row says, so a client that recorded an answer
            // has something to show a member.
            return ResponseEntity.ok(ApprovalMapper.toResponse(recorded.value()));
        }

        return problem(((Result.Failure<RecordedApproval>) result).error(), http.getRequestURI());
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        ProblemDetail problem = ExpectedFailures.toProblem(failure, instance);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * What the request is missing, or null when it can be acted on.
     *
     * <p>Only the fields the use case cannot be handed: a body, a member, and an id that is an id. Whether the run
     * exists, whether this member may answer it and whether they have already are answered by the use case and the
     * store, because those are conditions rather than malformations - and they arrive as refusals a client can
     * branch on.
     */
    private static DomainError malformedRequest(String runId, ApproveRunRequest request) {
        if (runId == null || runId.isBlank() || !isUuid(runId)) {
            return new ApprovalRefusals.RequestIncomplete("runId in the path, as a uuid");
        }
        if (request == null) {
            return new ApprovalRefusals.RequestIncomplete("body");
        }
        if (request.memberId() == null) {
            return new ApprovalRefusals.RequestIncomplete("memberId");
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
