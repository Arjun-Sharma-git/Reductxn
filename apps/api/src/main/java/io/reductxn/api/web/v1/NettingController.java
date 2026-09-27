package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.PreviewNetting;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.NettingPreviewMapper;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;
import io.reductxn.planning.api.SettlementPlan;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/groups/{groupId}/netting/preview}, per the contract.
 *
 * <p>An inbound adapter, and the only kind of class that may depend on HTTP. It depends on
 * {@link PreviewNetting} and never on an implementation or a repository, which is RULE B12 - "a
 * controller injecting a repository is the most common way this architecture dies".
 *
 * <p><strong>It reads the clock.</strong> The boundary is where "now" is decided, because nothing below
 * it may: the planner is pure (RULE B21) and the use case takes the instant it was given. Reading it here
 * means an {@code asOf} preview and a live one travel the same path, and a test can pin the clock and get
 * a reproducible answer.
 *
 * <p>It also does no mapping itself. The response is built by {@link NettingPreviewMapper}, so the shape
 * of the contract lives in one file rather than being spread across handlers.
 *
 * <p><strong>The caller, since ADR 0054, and the group id judged here rather than bound.</strong> A preview is the
 * plan a group is about to execute, so who is asking is settled before the path is - and the id is taken as a
 * {@code String} for the reason {@code GroupRunsController} gives, which this class only adopted with the enforcement:
 * binding it made Spring answer a malformed id before the handler ran, so an unauthenticated caller got a `400` about
 * its request rather than the `401` its session deserves. This was one of the two reads the client reached by pasting
 * an id, which is why the box it reached them from now takes a code.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/netting")
public class NettingController {

    private final PreviewNetting previewNetting;
    private final CallerResolver callers;
    private final Clock clock;

    public NettingController(PreviewNetting previewNetting, CallerResolver callers, Clock clock) {
        this.previewNetting = Objects.requireNonNull(previewNetting, "previewNetting");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param asOf optional; the contract's parameter for previewing a past moment. Defaults to now.
     */
    @GetMapping("/preview")
    public ResponseEntity<Object> preview(
            @PathVariable String groupId,
            @RequestParam(name = "asOf", required = false) Instant asOf,
            HttpServletRequest http) {

        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<SettlementPlan> plan = previewNetting.preview(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                UUID.fromString(groupId),
                asOf == null ? clock.instant() : asOf);

        if (plan instanceof Result.Failure<SettlementPlan> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(NettingPreviewMapper.toResponse(((Result.Ok<SettlementPlan>) plan).value()));
    }

    /** The path parameter, checked here rather than bound, for the reason the class comment gives. */
    private static DomainError malformedGroupId(String groupId) {
        if (groupId == null || groupId.isBlank() || !isUuid(groupId)) {
            return new RunRefusals.RequestIncomplete("groupId in the path, as a uuid");
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

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
