package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadGroupBalances;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.NetPositionResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;
import io.reductxn.netting.api.NetPosition;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/groups/{groupId}/balances}, per the contract.
 *
 * <p>An inbound adapter, and it depends on {@link ReadGroupBalances} rather than on a repository or an
 * implementation, which is RULE B12.
 *
 * <p><strong>It reads the clock, and it is the only place that does.</strong> The contract gives this operation no
 * {@code asOf}: a balance is the answer to "what do we owe each other now", and a caller who wants a past instant is
 * asking for the what-if the preview's {@code asOf} parameter already answers. Reading "now" here rather than in the
 * use case is the same choice the preview's controller made, for the same reason - so a test can pin the clock and
 * get a reproducible answer.
 *
 * <p>The response is a list of the contract's {@code NetPosition} schema, built by
 * {@link NetPositionResponse#of}, which is also what the preview's plan uses. One schema, one Java type, whichever
 * endpoint is answering.
 *
 * <p><strong>The caller is resolved first, since ADR 0054</strong>, and the group id is now checked here rather than
 * bound as a {@code UUID}. That is a change from the shorter route this class used to take, and it is the one this
 * slice forced: binding the id means Spring judges the path before the handler runs, so a request with no session and
 * a malformed id would answer a `400` about the id - telling an unauthenticated caller something about its request
 * when the answer to every other question here is `401`. The divergence was already recorded as "one of the two should
 * move" ({@code GroupRunsController}); this is the move, and it makes all nine of the group's reads answer in one
 * order: who is asking, then what was asked.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/balances")
public class BalancesController {

    private final ReadGroupBalances balances;
    private final CallerResolver callers;
    private final Clock clock;

    public BalancesController(ReadGroupBalances balances, CallerResolver callers, Clock clock) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public ResponseEntity<Object> balances(@PathVariable String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<List<NetPosition>> positions = balances.balances(
                ((Result.Ok<SignedInUser>) caller).value().id(), UUID.fromString(groupId), clock.instant());

        if (positions instanceof Result.Failure<List<NetPosition>> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(((Result.Ok<List<NetPosition>>) positions).value()
                .stream()
                .map(NetPositionResponse::of)
                .toList());
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
