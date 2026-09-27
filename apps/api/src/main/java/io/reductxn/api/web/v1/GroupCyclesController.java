package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.NewCycle;
import io.reductxn.api.port.in.OpenCycle;
import io.reductxn.api.port.in.OpenedCycle;
import io.reductxn.api.port.in.ReadGroupCycles;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.CycleRequest;
import io.reductxn.api.web.dto.SettlementCycleResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
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
 * <p><strong>It computes nothing and judges nothing about the cycle.</strong> There is no call to the planner and no
 * refusal for "nothing to settle". That absence is the one worth explaining: the group's commitments are this module's
 * rows, so the question is answerable here, and answering it here would be wrong - a commitment recorded tomorrow can
 * carry an instant inside this cycle, so the request that looks empty today is the ordinary way to settle a backdated
 * bill. The worker refuses an empty cycle when it computes it, which is the moment the question has an answer.
 *
 * <p><strong>What it does judge, since ADR 0049, is who is asking.</strong> This doc used to say there was no check
 * that the group exists, on the grounds that groups were not tables - true when it was written and false since
 * ADR 0043, and the contract's own paragraph about it is rewritten with this slice rather than left to contradict the
 * code. The caller is resolved first and the use case requires them to be a member of the group they are closing the
 * books of: a stranger holding an id gets the {@code 404} every other group write answers with.
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

    /** The contract's default for a list endpoint, and the same one the runs and cycles pages use. */
    private static final int DEFAULT_LIMIT = 20;

    private final OpenCycle openCycle;
    private final ReadGroupCycles readGroupCycles;
    private final CallerResolver callers;
    private final Clock clock;

    public GroupCyclesController(OpenCycle openCycle, ReadGroupCycles readGroupCycles, CallerResolver callers,
            Clock clock) {
        this.openCycle = Objects.requireNonNull(openCycle, "openCycle");
        this.readGroupCycles = Objects.requireNonNull(readGroupCycles, "readGroupCycles");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping
    public ResponseEntity<Object> open(
            @PathVariable String groupId,
            @RequestBody(required = false) CycleRequest request,
            HttpServletRequest http) {

        // Who is asking first, before the path or the body is judged: a request with no session is refused as such.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformed(groupId, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<OpenedCycle> opened = openCycle.open(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                new NewCycle(UUID.fromString(groupId), request.asOf(), clock.instant()));

        if (opened instanceof Result.Failure<OpenedCycle> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        OpenedCycle cycle = ((Result.Ok<OpenedCycle>) opened).value();

        ResponseEntity.BodyBuilder response = cycle.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                : ResponseEntity.ok();

        return response.body(SettlementCycleResponse.of(cycle.cycle()));
    }

    /**
     * {@code GET /v1/groups/{groupId}/cycles}: the cycles this group has closed, and the plan over each one.
     *
     * <p>The read that makes the write above observable. A member who asked for a settlement and a member whose cycle
     * the worker refused to plan both see nothing at all without it - the first has a row nobody reads, the second
     * has a cycle whose instant passes with no plan - and the {@code runId} on each row is what tells them apart from
     * a cycle that was merely asked for a moment ago.
     */
    @GetMapping
    public ResponseEntity<Object> list(
            @PathVariable String groupId,
            @RequestParam(name = "limit", required = false) Integer limit,
            HttpServletRequest http) {

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        List<SettlementCycleResponse> cycles = readGroupCycles
                .list(UUID.fromString(groupId), limit == null ? DEFAULT_LIMIT : limit)
                .stream()
                .map(SettlementCycleResponse::of)
                .toList();

        return ResponseEntity.ok(cycles);
    }

    /**
     * What the request is missing, or null when it can be acted on.
     *
     * <p>{@code api.request_incomplete} is the code {@code RunRefusals} already declares, and reusing it is the
     * intent rather than a shortcut: a code identifies a condition rather than an endpoint (RULE B20), and "the
     * request cannot be handed to the use case" is one condition however many endpoints meet it.
     */
    private static DomainError malformed(String groupId, CycleRequest request) {
        DomainError path = malformedGroupId(groupId);

        if (path != null) {
            return path;
        }
        if (request == null) {
            return new RunRefusals.RequestIncomplete("body");
        }
        if (request.asOf() == null) {
            return new RunRefusals.RequestIncomplete("asOf, the instant the books are closed at");
        }

        return null;
    }

    /**
     * The path parameter, which the contract declares as a uuid.
     *
     * <p>Taken as a {@code String} and checked here rather than bound as a {@code UUID}, so that a malformed id gets
     * the same coded problem body every other refusal gets - the reason {@code GroupRunsController} gives, and the
     * reason this class shares one code with it: a code identifies a condition rather than an endpoint (RULE B20).
     */
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
