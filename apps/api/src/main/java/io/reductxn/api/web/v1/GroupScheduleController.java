package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ClearGroupSchedule;
import io.reductxn.api.port.in.CycleSchedule;
import io.reductxn.api.port.in.ReadGroupSchedule;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SetGroupSchedule;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.CycleScheduleRequest;
import io.reductxn.api.web.dto.CycleScheduleResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code /v1/groups/{groupId}/schedule}: when a group's books close by themselves (ADR 0057).
 *
 * <p><strong>Three handlers, one path, and the path is singular because a group has one schedule.</strong> The contract
 * says the same: a `PUT` sets or changes it, a `GET` reads it, a `DELETE` stops the cadence. A plural path with an id in
 * it would promise a collection that does not exist - the shape ADR 0055 refused for a second kind of code: one thing is
 * not a list.
 *
 * <p><strong>All three resolve a caller and ask whether they are in the group.</strong> It is a read of and a write to a
 * group's books, so ADR 0054's rule governs the read and ADR 0048's the writes. The clock is read here rather than
 * inside the use case (RULE B21), and handed over once as {@code setAt}: the row's stamp and the "now" the due instant is
 * judged against are the same instant, so a schedule cannot look like it was decided at a different moment from the one
 * it was measured at.
 */
@RestController
@RequestMapping("/v1")
public class GroupScheduleController {

    private final SetGroupSchedule setGroupSchedule;
    private final ReadGroupSchedule readGroupSchedule;
    private final ClearGroupSchedule clearGroupSchedule;
    private final CallerResolver callers;
    private final Clock clock;

    public GroupScheduleController(
            SetGroupSchedule setGroupSchedule,
            ReadGroupSchedule readGroupSchedule,
            ClearGroupSchedule clearGroupSchedule,
            CallerResolver callers,
            Clock clock) {

        this.setGroupSchedule = Objects.requireNonNull(setGroupSchedule, "setGroupSchedule");
        this.readGroupSchedule = Objects.requireNonNull(readGroupSchedule, "readGroupSchedule");
        this.clearGroupSchedule = Objects.requireNonNull(clearGroupSchedule, "clearGroupSchedule");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Sets or changes the group's schedule. */
    @PutMapping("/groups/{groupId}/schedule")
    public ResponseEntity<Object> set(
            @PathVariable String groupId,
            @RequestBody(required = false) CycleScheduleRequest request,
            HttpServletRequest http) {

        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformed(groupId, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<CycleSchedule> schedule = setGroupSchedule.set(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                request.toCommand(UUID.fromString(groupId), clock.instant()));

        if (schedule instanceof Result.Failure<CycleSchedule> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(CycleScheduleResponse.of(((Result.Ok<CycleSchedule>) schedule).value()));
    }

    /** When the group's books close next. */
    @GetMapping("/groups/{groupId}/schedule")
    public ResponseEntity<Object> read(@PathVariable String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<CycleSchedule> schedule = readGroupSchedule.read(
                ((Result.Ok<SignedInUser>) caller).value().id(), UUID.fromString(groupId));

        if (schedule instanceof Result.Failure<CycleSchedule> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(CycleScheduleResponse.of(((Result.Ok<CycleSchedule>) schedule).value()));
    }

    /** Stops the cadence, so nothing closes the group's books by itself. */
    @DeleteMapping("/groups/{groupId}/schedule")
    public ResponseEntity<Object> clear(@PathVariable String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<Void> cleared = clearGroupSchedule.clear(
                ((Result.Ok<SignedInUser>) caller).value().id(), UUID.fromString(groupId));

        if (cleared instanceof Result.Failure<Void> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.noContent().build();
    }

    private static DomainError malformed(String groupId, CycleScheduleRequest request) {
        DomainError malformedId = malformedGroupId(groupId);

        if (malformedId != null) {
            return malformedId;
        }

        if (request == null || !request.isComplete()) {
            return new RunRefusals.RequestIncomplete("everyDays and nextDueAt in the body, both required");
        }

        return null;
    }

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

    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}