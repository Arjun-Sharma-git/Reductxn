package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.AddMember;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.GroupMember;
import io.reductxn.api.port.in.MemberRefusals;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.in.ReadGroupMembers;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.MemberRequest;
import io.reductxn.api.web.dto.MemberResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET} and {@code POST /v1/groups/{groupId}/members}, per the contract: who is in the group, and recording
 * somebody as a member.
 *
 * <p><strong>Both operations have been in the contract since the obligations slice, and neither existed here.</strong>
 * The read is the one that hurt: the client's member catalogue has called it all along, three screens name people
 * (the obligations list and its filters, the recording form, and a run's answers), so in development each of them
 * fell back to showing a uuid - and no test could see it, because the client's tests stub the API and this module had
 * never asked for members. The write is here because a read with no writer is a screen that is empty forever: with
 * membership as data (ADR 0035), something has to record it, and the candidate that needs no identity is the caller.
 *
 * <p><strong>Both operations require a member of the group, and the read joined the write in ADR 0054.</strong> The
 * write has been one since ADR 0048: adding somebody to a group is an act within it, so the boundary resolves the
 * caller and the use case asks whether they are in that group. The read was the last of the group's reads still
 * serving a bare uuid, and this doc said so in as many words - "the read serves a bare uuid - it is where a name comes
 * from for screens that name people - and it is one of the group's reads that never knew who was asking, which the
 * plan records rather than pretends". The plan's record was the honest half; the other half was that a list of a
 * group's members is the group's own business, and a name is what a stranger most wants from an id.
 * <strong>Membership, not {@code admin}, for both.</strong> The role is recorded and nothing reads it yet, so an
 * admin-only rule here would invent a privilege whose only effect is to make these operations unusable for everybody
 * the invitation flow creates - every member but the creator (ADR 0043).
 *
 * <p><strong>Idempotent by rule, so the status carries the answer.</strong> {@code (groupId, userId)} is the table's
 * primary key, so a second request for the same person is a {@code 200} with the member as they stand - the
 * contract's two response shapes - and not a {@code 409}: a caller asking twice has made no mistake, and answering
 * an idempotent call with a conflict is what P8 exists to avoid.
 */
@RestController
@RequestMapping("/v1/groups/{groupId}/members")
public class GroupMembersController {

    /** The contract's {@code displayName} maxLength, which lives in the port with the refusal that carries it. */
    private static final int NAME_MAX_LENGTH = MemberRefusals.MAX_NAME_LENGTH;

    /** What a caller who does not say gets: the ordinary member, which is the common case. */
    private static final String DEFAULT_ROLE = "member";

    private final ReadGroupMembers readGroupMembers;
    private final AddMember addMember;
    private final CallerResolver callers;
    private final Clock clock;

    public GroupMembersController(ReadGroupMembers readGroupMembers, AddMember addMember, CallerResolver callers,
            Clock clock) {
        this.readGroupMembers = Objects.requireNonNull(readGroupMembers, "readGroupMembers");
        this.addMember = Objects.requireNonNull(addMember, "addMember");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public ResponseEntity<Object> list(@PathVariable String groupId, HttpServletRequest http) {
        // Who is asking first: this read names people, so a request with no session is refused as a 401 rather than
        // being told something about the id it sent (ADR 0054). The doc above this class used to say the read never
        // knew who was asking; it does now, and it is the same caller the write beside it resolves.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<List<GroupMember>> members = readGroupMembers.list(
                ((Result.Ok<SignedInUser>) caller).value().id(), UUID.fromString(groupId));

        if (members instanceof Result.Failure<List<GroupMember>> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(((Result.Ok<List<GroupMember>>) members).value()
                .stream()
                .map(MemberResponse::of)
                .toList());
    }

    @PostMapping
    public ResponseEntity<Object> record(
            @PathVariable String groupId,
            @RequestBody(required = false) MemberRequest request,
            HttpServletRequest http) {

        // Who is asking first, as at every boundary that needs a caller (ADR 0043, 0045, 0047): a request with no session
        // is refused as such rather than as a malformed one.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformed(groupId, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<AddedMember> added = addMember.add(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                new NewMember(
                        UUID.fromString(groupId),
                        UUID.fromString(request.userId()),
                        request.displayName(),
                        request.role() == null ? DEFAULT_ROLE : request.role(),
                        clock.instant()));

        if (added instanceof Result.Failure<AddedMember> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        AddedMember member = ((Result.Ok<AddedMember>) added).value();

        ResponseEntity.BodyBuilder response = member.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                : ResponseEntity.ok();

        return response.body(MemberResponse.of(member.member()));
    }

    private static DomainError malformed(String groupId, MemberRequest request) {
        DomainError path = malformedGroupId(groupId);

        if (path != null) {
            return path;
        }
        if (request == null) {
            return new RunRefusals.RequestIncomplete("body");
        }
        if (request.userId() == null || !isUuid(request.userId())) {
            return new RunRefusals.RequestIncomplete("userId, the person, as a uuid");
        }
        if (request.displayName() == null || request.displayName().isBlank()) {
            return new RunRefusals.RequestIncomplete("displayName, what the group calls them");
        }
        if (request.displayName().length() > NAME_MAX_LENGTH) {
            return new MemberRefusals.NameTooLong(request.displayName().length(), NAME_MAX_LENGTH);
        }
        if (request.role() != null && !MemberRefusals.ROLES.contains(request.role())) {
            // The schema's own vocabulary, named in the detail rather than answered with a generic message: a caller
            // who sent "owner" needs the two words this API stores, and the table's CHECK is what decides them.
            return new RunRefusals.RequestIncomplete("role, one of " + String.join(" or ", MemberRefusals.ROLES));
        }

        return null;
    }

    /**
     * The path parameter, checked here rather than bound as a {@code UUID}, so a malformed id gets the same coded
     * problem body every other refusal gets - the reason {@code GroupRunsController} gives, and
     * `api.request_incomplete` is shared with it because a code identifies a condition rather than an endpoint
     * (RULE B20).
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
