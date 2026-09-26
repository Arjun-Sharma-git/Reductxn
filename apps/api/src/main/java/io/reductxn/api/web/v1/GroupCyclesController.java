package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.NewCycle;
import io.reductxn.api.port.in.OpenCycle;
import io.reductxn.api.port.in.OpenedCycle;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.web.dto.CycleRequest;
import io.reductxn.api.web.dto.SettlementCycleResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
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
 * {@code POST /v1/groups/{groupId}/cycles}: closing a group's books at an instant, which is how a settlement
 * starts.
 *
 * <p><strong>The endpoint that turns "nothing schedules a cycle" into a policy's problem.</strong> Until ADR 0032 a
 * cycle and the plan over it were one act, so there was nothing to ask for: an operator could compute, and nobody
 * else could do anything. Now the two are separate - the worker opens nothing on its own and computes every cycle
 * that has no run - so this is the surface where a member, or a client acting for a schedule, says which instant
 * their group's books close at. What it writes is a row in another module's table, with the costs ADR 0020
 * documents and for the same reason: the member's surface is here, the run is there, and the two deployables do not
 * depend on each other.
 *
 * <p><strong>It computes nothing and judges nothing.</strong> There is no call to the planner, no check that the
 * group exists, and no refusal for "nothing to settle". The last of those is the one worth explaining: the group's
 * commitments are this module's rows, so the question is answerable here, and answering it here would be wrong - a
 * commitment recorded tomorrow can carry an instant inside this cycle, so the request that looks empty today is the
 * ordinary way to settle a backdated bill. The worker refuses an empty cycle when it computes it, which is the
 * moment the question has an answer.
 *
 * <p><strong>{@code 201} when this request opened the cycle, {@code 200} when it was already open.</strong> The
 * distinction is the store's answer rather than a second question asked before the write - check-then-insert is the
 * race the unique constraint on {@code (group_id, as_of)} exists to settle - and it is what makes a retry after a
 * timeout, a double click and a policy firing twice all safe. Both responses carry the cycle, because both are
 * successes and the client needs the id either way.
 *
 * <p>The group id is parsed here rather than bound as a {@code UUID}, for the reason {@code GroupRunsController}
 * gives: a malformed id would otherwise be answered by Spring with a {@code 400} whose body carries no code.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/cycles")
public class GroupCyclesController {

    private final OpenCycle openCycle;
    private final Clock clock;

    public GroupCyclesController(OpenCycle openCycle, Clock clock) {
        this.openCycle = Objects.requireNonNull(openCycle, "openCycle");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping
    public ResponseEntity<Object> open(
            @PathVariable String groupId,
            @RequestBody(required = false) CycleRequest request,
            HttpServletRequest http) {

        DomainError malformed = malformed(groupId, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        OpenedCycle opened = openCycle.open(
                new NewCycle(UUID.fromString(groupId), request.asOf(), clock.instant()));

        ResponseEntity.BodyBuilder response = opened.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                : ResponseEntity.ok();

        return response.body(SettlementCycleResponse.of(opened));
    }

    /**
     * What the request is missing, or null when it can be acted on.
     *
     * <p>{@code api.request_incomplete} is the code {@code RunRefusals} already declares, and reusing it is the
     * intent rather than a shortcut: a code identifies a condition rather than an endpoint (RULE B20), and "the
     * request cannot be handed to the use case" is one condition however many endpoints meet it.
     */
    private static DomainError malformed(String groupId, CycleRequest request) {
        if (groupId == null || groupId.isBlank() || !isUuid(groupId)) {
            return new RunRefusals.RequestIncomplete("groupId in the path, as a uuid");
        }
        if (request == null) {
            return new RunRefusals.RequestIncomplete("body");
        }
        if (request.asOf() == null) {
            return new RunRefusals.RequestIncomplete("asOf, the instant the books are closed at");
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
