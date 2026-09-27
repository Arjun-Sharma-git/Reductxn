package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.AcceptInvite;
import io.reductxn.api.port.in.AcceptedInvitation;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.CreateInvite;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.MemberRefusals;
import io.reductxn.api.port.in.NewInvite;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.AcceptInviteRequest;
import io.reductxn.api.web.dto.InviteResponse;
import io.reductxn.api.web.dto.MemberResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

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
 * {@code POST /v1/groups/{groupId}/invites} and {@code POST /v1/invites/{code}/accept}: inviting somebody, and arriving.
 *
 * <p>One controller for both because they are one flow with one subject - an invitation - and the two operations share
 * their validation, their clock and their problem helper. The class-level mapping is {@code /v1} rather than a resource
 * prefix, because the contract gives the two paths no common prefix: creation is addressed by the group and acceptance by
 * the code, which is the contract's own decision (the code carries the group, which is why it is globally unique).
 *
 * <p><strong>The acceptance is about the caller; the creation is still about nothing.</strong> Somebody handing in a code
 * joins *as themselves*, so this controller resolves the session and refuses a request without one - which is the
 * contract's promise kept, one slice after `GET /me` made it keepable (ADR 0042). Creating an invitation is the other
 * half and is still unauthorised: nothing stops a caller making a code for a group they are not in, which the contract
 * says where the operation is described and this class does not pretend otherwise. What the two now have in common is
 * only the flow; what they do about identity differs, which is why the difference is stated here rather than implied.
 *
 * <p><strong>A code is read in the case it was typed.</strong> The alphabet is uppercase and the table refuses anything
 * else, so a code typed in lowercase would be a {@code 404} whose cause is invisible in the request. The path variable
 * is therefore trimmed and uppercased here - one normalisation, at the boundary, where the wire becomes a use case - and
 * the code stored and returned is never rewritten.
 */
@RestController
@RequestMapping("/v1")
public class InvitesController {

    private final CreateInvite createInvite;
    private final AcceptInvite acceptInvite;
    private final CallerResolver callers;
    private final Clock clock;

    public InvitesController(CreateInvite createInvite, AcceptInvite acceptInvite, CallerResolver callers, Clock clock) {
        this.createInvite = Objects.requireNonNull(createInvite, "createInvite");
        this.acceptInvite = Objects.requireNonNull(acceptInvite, "acceptInvite");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A code that lets somebody join the group.
     *
     * <p>{@code 201}, and the contract is what says so: an invitation was created and its content is the code, which is
     * the whole of what the caller gets back.
     */
    @PostMapping("/groups/{groupId}/invites")
    public ResponseEntity<Object> create(@PathVariable String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<CreatedInvite> created = createInvite.create(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                new NewInvite(UUID.fromString(groupId), clock.instant()));

        if (created instanceof Result.Failure<CreatedInvite> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        CreatedInvite invite = ((Result.Ok<CreatedInvite>) created).value();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InviteResponse(invite.code(), invite.expiresAt(), invite.maxUses()));
    }

    /**
     * Somebody hands in a code and joins the group it is for.
     *
     * <p><strong>The status carries what happened, and the contract was given the second one.</strong> {@code 201} when
     * this request is what recorded the membership and {@code 200} when the group already had them - the same pair the
     * members write answers with, because accepting a code is a way of becoming a member and "already one of us" is not a
     * mistake (P8). The contract originally declared only {@code 200}; the {@code 201} is an addition, and the operation's
     * description now names both.
     *
     * <p>The plain "Joined" is not what a caller needs to see, either: the body is the member, so a client can show the
     * group's name for them rather than only that something worked.
     */
    @PostMapping("/invites/{code}/accept")
    public ResponseEntity<Object> accept(
            @PathVariable String code,
            @RequestBody(required = false) AcceptInviteRequest request,
            HttpServletRequest http) {

        // Before the body, and that order is the contract's: who is asking is the question, and a request with no
        // session is refused as such rather than as a malformed one. The three group operations and the void resolve
        // their caller first for the same reason (ADR 0043, 0045).
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedAcceptance(code, request);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        AcceptedInvitation accepted = new AcceptedInvitation(
                trimmed(code),
                ((Result.Ok<SignedInUser>) caller).value().id(),
                request.displayName(),
                clock.instant());

        Result<AddedMember> result = acceptInvite.accept(accepted);

        if (result instanceof Result.Ok<AddedMember> joined) {
            ResponseEntity.BodyBuilder response = joined.value().created()
                    ? ResponseEntity.status(HttpStatus.CREATED)
                    : ResponseEntity.ok();

            return response.body(MemberResponse.of(joined.value().member()));
        }

        return problem(((Result.Failure<AddedMember>) result).error(), http.getRequestURI());
    }

    /** What the acceptance is missing, or null when it can be acted on. */
    private static DomainError malformedAcceptance(String code, AcceptInviteRequest request) {
        if (code == null || code.isBlank()) {
            return new RunRefusals.RequestIncomplete("code in the path, the invitation's code");
        }
        if (request == null) {
            return new RunRefusals.RequestIncomplete("body");
        }
        // No `userId` branch: the field is not in the contract, so there is nothing here to validate - the person
        // joining came from the session. An unknown property in the body is ignored rather than refused, which is
        // Spring's default and the behaviour the contract's schema description states.
        if (request.displayName() == null || request.displayName().isBlank()) {
            return new RunRefusals.RequestIncomplete("displayName, what the group will call them");
        }
        if (request.displayName().length() > MemberRefusals.MAX_NAME_LENGTH) {
            return new MemberRefusals.NameTooLong(request.displayName().length(), MemberRefusals.MAX_NAME_LENGTH);
        }

        return null;
    }

    private static DomainError malformedGroupId(String groupId) {
        if (groupId == null || groupId.isBlank() || !isUuid(groupId)) {
            return new RunRefusals.RequestIncomplete("groupId in the path, as a uuid");
        }

        return null;
    }

    /**
     * The code as it arrived, minus whitespace from a paste.
     *
     * <p>Nothing else is normalised here, and RULE B20 is why: the case of a code is the <em>store's</em> alphabet, so
     * canonicalising is the store's job ({@code JooqInvitations} does it as arithmetic over its own alphabet). An earlier
     * version of this method uppercased with {@code Locale.ROOT} - locale-<em>safe</em>, and still a locale - which is
     * exactly the violation that rule exists for, and the second time this module has made that mistake.
     */
    private static String trimmed(String code) {
        return code.strip();
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
