package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.NewObligation;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.RecordObligation;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.web.dto.ObligationMapper;
import io.reductxn.api.web.dto.RecordObligationRequest;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;

/**
 * {@code POST /v1/obligations}, per the contract.
 *
 * <p>An inbound adapter, and it depends on {@link RecordObligation} rather than on an implementation or a
 * repository (RULE B12). It reads the clock, because the boundary is where "now" is decided (RULE B21), and it
 * checks the request's shape before the use case sees it - here rather than inside the use case, because a
 * missing field is a malformed request while everything the use case refuses is a request it understood.
 *
 * <p><strong>It returns {@code ResponseEntity<Object>}, which costs the signature some clarity.</strong> The
 * contract answers {@code 201} with an obligation and a failure with a problem, and an expected failure
 * arrives as a {@link Result} value rather than as an exception - so there is nothing to throw and two shapes
 * to return. The alternatives were worse: throwing so that {@code DomainErrorResolver} could answer would
 * report a refusal as a bug, and narrowing the return type would need the failure to be an exception again.
 *
 * <p>The idempotency header is declared optional even though the contract requires it, so that a missing one
 * gets the same {@code problem+json} body every other refusal gets: Spring's own missing-header error is a
 * {@code 400} with a body a client cannot branch on.
 */
@RestController
@RequestMapping("/v1/obligations")
public class ObligationsController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    /** The contract's {@code memo} maxLength. Refused rather than truncated. */
    private static final int MEMO_MAX_LENGTH = 280;

    private final RecordObligation recordObligation;
    private final Clock clock;

    public ObligationsController(RecordObligation recordObligation, Clock clock) {
        this.recordObligation = Objects.requireNonNull(recordObligation, "recordObligation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping
    public ResponseEntity<Object> record(
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) RecordObligationRequest request,
            HttpServletRequest http) {

        DomainError malformed = malformedRequest(request, idempotencyKey);
        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        NewObligation command = ObligationMapper.toCommand(request, clock.instant(), idempotencyKey);
        Result<RecordedObligation> result = recordObligation.record(command);

        if (result instanceof Result.Ok<RecordedObligation> recorded) {
            // 201 for a replayed request too. The contract says "Recorded", and the obligation was recorded -
            // once, by the first attempt. Answering anything else would make a retry look like it had done
            // something.
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ObligationMapper.toResponse(recorded.value()));
        }

        return problem(((Result.Failure<RecordedObligation>) result).error(), http.getRequestURI());
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        ProblemDetail problem = ExpectedFailures.toProblem(failure, instance);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /**
     * The fields the contract requires, or null when the request is complete enough to act on.
     *
     * <p>Only for the fields the use case cannot be handed at all: {@code NewObligation} requires them
     * non-null, so a null here would reach a constructor and become a {@code 500}. An amount of zero is
     * deliberately not checked - it is a number the client stated, and whether a commitment of nothing is
     * allowed is a domain question the use case answers with a code of its own.
     */
    private static DomainError malformedRequest(
            RecordObligationRequest request, String idempotencyKey) {

        if (request == null) {
            return new ObligationRefusals.RequestIncomplete("body");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new ObligationRefusals.RequestIncomplete(IDEMPOTENCY_KEY_HEADER);
        }
        if (request.groupId() == null) {
            return new ObligationRefusals.RequestIncomplete("groupId");
        }
        if (request.debtorId() == null) {
            return new ObligationRefusals.RequestIncomplete("debtorId");
        }
        if (request.creditorId() == null) {
            return new ObligationRefusals.RequestIncomplete("creditorId");
        }
        if (request.amount() == null) {
            return new ObligationRefusals.RequestIncomplete("amount");
        }
        if (request.amount().currency() == null || request.amount().currency().isBlank()) {
            return new ObligationRefusals.RequestIncomplete("amount.currency");
        }
        if (request.memo() == null || request.memo().isBlank()) {
            return new ObligationRefusals.RequestIncomplete("memo");
        }
        if (request.memo().length() > MEMO_MAX_LENGTH) {
            return new ObligationRefusals.MemoTooLong(request.memo().length());
        }

        return null;
    }
}
