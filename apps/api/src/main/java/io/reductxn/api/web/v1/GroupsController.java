package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.CreateGroup;
import io.reductxn.api.port.in.CreatedGroup;
import io.reductxn.api.port.in.GroupView;
import io.reductxn.api.port.in.NewGroup;
import io.reductxn.api.port.in.ReadGroup;
import io.reductxn.api.port.in.ReadGroups;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.GroupResponse;
import io.reductxn.api.web.dto.NewGroupBody;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The three group operations: what I belong to, create one, and one group's own screen.
 *
 * <p><strong>This controller is where enforcement starts, and it says so rather than pretending it happened
 * everywhere.</strong> ADR 0042 recorded that requiring a token on every boundary would break every existing caller at
 * once. These three operations are new, so there is nothing to break - and they cannot be answered without a caller
 * anyway: "the groups you belong to" is a question about a person. So they read the token, and the older boundaries keep
 * taking what the request says until a slice that owns that change. What is not true is that this is enforcement done: it
 * is enforcement where it is free (ADR 0043).
 *
 * <p><strong>The caller is resolved once, before anything else.</strong> A request with no usable session is refused
 * before the body is looked at, because "who is asking" is not something a body can answer, and a malformed body should not
 * be able to change which refusal a caller receives.
 */
@RestController
@RequestMapping("/v1/groups")
public class GroupsController {

    private final CreateGroup createGroup;
    private final ReadGroups readGroups;
    private final ReadGroup readGroup;
    private final CallerResolver callers;
    private final Clock clock;

    public GroupsController(CreateGroup createGroup, ReadGroups readGroups, ReadGroup readGroup, CallerResolver callers,
            Clock clock) {
        this.createGroup = Objects.requireNonNull(createGroup, "createGroup");
        this.readGroups = Objects.requireNonNull(readGroups, "readGroups");
        this.readGroup = Objects.requireNonNull(readGroup, "readGroup");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The groups this caller belongs to, most recently joined first. */
    @GetMapping
    public ResponseEntity<Object> list(HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        List<GroupResponse> groups = readGroups.of(caller(((Result.Ok<SignedInUser>) caller).value())).stream()
                .map(GroupResponse::of)
                .toList();

        return ResponseEntity.ok(groups);
    }

    /**
     * Create a group, which makes its creator a member.
     *
     * <p>The idempotency key is required by the contract's header, so a request without one is refused as incomplete rather
     * than answered twice - the same treatment a missing body field gets, and for the same reason: the contract says the
     * request cannot be made without it.
     */
    @PostMapping
    public ResponseEntity<Object> create(@RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) NewGroupBody body, HttpServletRequest http) {

        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = missing(body == null ? null : body.name(), "name");

        if (malformed == null) {
            malformed = missing(body.currency(), "currency");
        }

        if (malformed == null) {
            malformed = missing(key, "Idempotency-Key");
        }

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<CreatedGroup> created = createGroup.create(new NewGroup(
                body.name(),
                body.currency(),
                body.reportingCurrency(),
                body.settlementTimezone(),
                body.maxMembers() == null ? NewGroupBody.DEFAULT_MAX_MEMBERS : body.maxMembers(),
                ((Result.Ok<SignedInUser>) caller).value(),
                key,
                clock.instant()));

        if (created instanceof Result.Ok<CreatedGroup> made) {
            // 201 when this call is what created the group, 200 when the key had already done so. The same status pair the
            // obligation, member and invitation writes answer their replays with (P8): a retry is not an error, and a client
            // that got a second 201 for one group would be told that two exist.
            HttpStatus status = made.value().created() ? HttpStatus.CREATED : HttpStatus.OK;

            return ResponseEntity.status(status).body(GroupResponse.of(made.value().group()));
        }

        return problem(((Result.Failure<CreatedGroup>) created).error(), http.getRequestURI());
    }

    /** One group, if the caller is in it. */
    @GetMapping("/{groupId}")
    public ResponseEntity<Object> read(@PathVariable("groupId") String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = uuid(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<GroupView> group = readGroup.read(UUID.fromString(groupId), caller(((Result.Ok<SignedInUser>) caller).value()));

        if (group instanceof Result.Ok<GroupView> found) {
            return ResponseEntity.ok(GroupResponse.of(found.value()));
        }

        return problem(((Result.Failure<GroupView>) group).error(), http.getRequestURI());
    }

    private static UUID caller(SignedInUser user) {
        return user.id();
    }

    /**
     * A group id that is not a uuid, answered the way every other malformed parameter in this module is.
     *
     * <p>The general {@code api.request_incomplete} rather than "no such group": a typo is not a permission question, and
     * answering it with the read's {@code 404} would tell a client to stop looking for a group that does exist.
     */
    private static DomainError uuid(String value) {
        if (value == null || value.isBlank()) {
            return new RunRefusals.RequestIncomplete("groupId");
        }

        try {
            UUID.fromString(value);

            return null;
        } catch (IllegalArgumentException notAnId) {
            return new RunRefusals.RequestIncomplete("groupId");
        }
    }

    private static DomainError missing(String value, String field) {
        return value == null || value.isBlank() ? new RunRefusals.RequestIncomplete(field) : null;
    }

    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
