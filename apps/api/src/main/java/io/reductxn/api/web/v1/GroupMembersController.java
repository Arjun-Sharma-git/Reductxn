package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.AddMember;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.MemberRefusals;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.in.ReadGroupMembers;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.web.dto.MemberRequest;
import io.reductxn.api.web.dto.MemberResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;

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
 * <p><strong>The write is unauthorised, and says so rather than pretending.</strong> The contract's summary used to
 * claim \"admin only\", which this build cannot honour - there is no identity, so no `admin` row authorises anything.
 * This is the stance the decision endpoint already takes, where the member is whoever the body says (ADR 0020), and
 * the identity gap is recorded rather than papered over.
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
    private final Clock clock;

    public GroupMembersController(ReadGroupMembers readGroupMembers, AddMember addMember, Clock clock) {
        this.readGroupMembers = Objects.requireNonNull(readGroupMembers, "readGroupMembers");
        this.addMember = Objects.requireNonNull(addMember, "addMember");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @GetMapping
    public ResponseEntity<Object> list(@PathVariable String groupId, HttpServletRequest http) {
        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        List<MemberResponse> members = readGroupMembers.list(UUID.fromString(groupId))
                .stream()
                .map(MemberResponse::of)
                .toList();

        return ResponseEntity.ok(members);
    }

    @PostMapping
    public ResponseEntity<Object> record(
            @PathVariable String groupId,
            @RequestBody(required = false) MemberRequest request,
            HttpServletRequest http) {

        DomainError malformed = malformed(groupId, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        AddedMember added = addMember.add(new NewMember(
                UUID.fromString(groupId),
                UUID.fromString(request.userId()),
                request.displayName(),
                request.role() == null ? DEFAULT_ROLE : request.role(),
                clock.instant()));

        ResponseEntity.BodyBuilder response = added.created()
                ? ResponseEntity.status(HttpStatus.CREATED)
                : ResponseEntity.ok();

        return response.body(MemberResponse.of(added.member()));
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
