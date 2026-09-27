package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.NewObligation;
import io.reductxn.api.port.in.ObligationFilter;
import io.reductxn.api.port.in.ObligationRefusals;
import io.reductxn.api.port.in.ObligationStatus;
import io.reductxn.api.port.in.ReadObligations;
import io.reductxn.api.port.in.RecordObligation;
import io.reductxn.api.port.in.RecordedObligation;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.port.in.VoidObligation;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.ObligationMapper;
import io.reductxn.api.web.dto.ObligationResponse;
import io.reductxn.api.web.dto.RecordObligationRequest;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
    private final ReadObligations readObligations;
    private final VoidObligation voidObligation;
    private final CallerResolver callers;
    private final Clock clock;

    public ObligationsController(RecordObligation recordObligation, ReadObligations readObligations,
            VoidObligation voidObligation, CallerResolver callers, Clock clock) {
        this.recordObligation = Objects.requireNonNull(recordObligation, "recordObligation");
        this.readObligations = Objects.requireNonNull(readObligations, "readObligations");
        this.voidObligation = Objects.requireNonNull(voidObligation, "voidObligation");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public ResponseEntity<Object> list(
            @RequestParam(name = "groupId", required = false) String groupId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "memberId", required = false) String memberId,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "limit", required = false) String limit,
            HttpServletRequest http) {

        DomainError malformed = malformedQuery(groupId, status, memberId, from, to, sort, limit);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        List<ObligationResponse> obligations = readObligations.list(new ObligationFilter(
                        UUID.fromString(groupId),
                        status == null ? null : ObligationStatus.fromWire(status).orElseThrow(),
                        memberId == null ? null : UUID.fromString(memberId),
                        from == null ? null : LocalDate.parse(from),
                        to == null ? null : LocalDate.parse(to),
                        sort == null ? ObligationFilter.DEFAULT_SORT : ObligationFilter.Sort.fromWire(sort).orElseThrow(),
                        limit == null ? ObligationFilter.DEFAULT_LIMIT : Integer.parseInt(limit)))
                .stream()
                .map(ObligationMapper::toResponse)
                .toList();

        return ResponseEntity.ok(obligations);
    }

    /**
     * Every query parameter read as a string and checked here, rather than bound to its type.
     *
     * <p>A {@code UUID} or {@code LocalDate} parameter makes Spring answer a bad value with its own {@code 400},
     * whose body carries no code - and a client that cannot branch on a code has to parse prose (RULE B20). It is the
     * same compromise {@code GroupRunsController} makes for its path variable, and the reason {@code limit} arrives
     * as a string too: a limit of "many" is a malformed request rather than a server error.
     */
    private static DomainError malformedQuery(
            String groupId, String status, String memberId, String from, String to, String sort, String limit) {

        if (groupId == null || !isUuid(groupId)) {
            return new ObligationRefusals.RequestIncomplete("groupId, as a uuid");
        }
        if (status != null && ObligationStatus.fromWire(status).isEmpty()) {
            return new ObligationRefusals.RequestIncomplete(
                    "status, one of " + ObligationStatus.vocabulary());
        }
        if (memberId != null && !isUuid(memberId)) {
            return new ObligationRefusals.RequestIncomplete("memberId, as a uuid");
        }
        if (from != null && !isDate(from)) {
            return new ObligationRefusals.RequestIncomplete("from, a date as yyyy-MM-dd");
        }
        if (to != null && !isDate(to)) {
            return new ObligationRefusals.RequestIncomplete("to, a date as yyyy-MM-dd");
        }
        if (sort != null && ObligationFilter.Sort.fromWire(sort).isEmpty()) {
            return new ObligationRefusals.RequestIncomplete(
                    "sort, " + ObligationFilter.Sort.vocabulary());
        }
        if (limit != null && !isWholeNumber(limit)) {
            return new ObligationRefusals.RequestIncomplete("limit, a whole number");
        }

        return null;
    }

    private static boolean isDate(String candidate) {
        try {
            LocalDate.parse(candidate);

            return true;
        } catch (DateTimeParseException notADate) {
            return false;
        }
    }

    private static boolean isWholeNumber(String candidate) {
        try {
            Integer.parseInt(candidate);

            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static boolean isUuid(String candidate) {
        try {
            UUID.fromString(candidate);

            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
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

    /**
     * The void.
     *
     * <p>The path's id arrives as a string and is parsed here, so a malformed one is this module's own refusal
     * ({@code api.obligation_request_incomplete}) rather than a Spring conversion error - the same choice the group
     * controller makes for the same reason: a client should never see a shape the contract does not describe.
     */
    @PostMapping("/{obligationId}/void")
    public ResponseEntity<Object> voidOne(@PathVariable("obligationId") String obligationId, HttpServletRequest http) {

        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        if (!isUuid(obligationId)) {
            return problem(new ObligationRefusals.RequestIncomplete("obligationId"), http.getRequestURI());
        }

        Result<RecordedObligation> voided = voidObligation.voidOne(
                UUID.fromString(obligationId),
                ((Result.Ok<SignedInUser>) caller).value().id(),
                clock.instant());

        if (voided instanceof Result.Ok<RecordedObligation> row) {
            return ResponseEntity.ok(ObligationMapper.toResponse(row.value()));
        }

        return problem(((Result.Failure<RecordedObligation>) voided).error(), http.getRequestURI());
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
