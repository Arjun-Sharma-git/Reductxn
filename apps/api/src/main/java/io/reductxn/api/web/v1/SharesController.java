package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.CreateShare;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.NewShare;
import io.reductxn.api.port.in.ReadSharedView;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SharedView;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.InviteResponse;
import io.reductxn.api.web.dto.SharedViewResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code POST /v1/groups/{groupId}/shares} and {@code GET /v1/shared/{code}}: a link that lets somebody look, and what
 * it shows (ADR 0055).
 *
 * <p><strong>A controller of its own rather than two more handlers on {@code InvitesController}.</strong> The two
 * controllers are the two audiences: that one has somebody arriving to join a group - it resolves a caller for the
 * acceptance and answers with a member - and this one has a group handing out a view of what it owes, which resolves a
 * caller for the creation and then resolves nobody at all for the read. What they share is a store and a code, which is
 * the point of the slice: one instrument, two purposes. The class-level mapping is {@code /v1} for the same reason
 * {@code InvitesController}'s is - the contract gives these paths no common prefix, since the creation is addressed by
 * the group and the read by the code.
 *
 * <p><strong>The creation resolves a caller; the read deliberately does not.</strong> A link is something a group hands
 * out, so making one is a member's act and goes through {@code CreateShare} to the same {@code Groups.isMember} check
 * every other write uses (ADR 0048). Reading one is the operation the code exists for, so it resolves nobody - the same
 * shape {@code GET /invites/{code}} has, and the contract gives both {@code security: []}.
 *
 * <p><strong>A code is read in the case it was typed</strong>, like the acceptance's: trimmed here, and canonicalised by
 * the store, which is the only layer that knows the alphabet. {@code InvitesController.trimmed} carries the full
 * argument; this class repeats one line of it rather than reaching into another controller for a private method.
 */
@RestController
@RequestMapping("/v1")
public class SharesController {

    private final CreateShare createShare;
    private final ReadSharedView sharedView;
    private final CallerResolver callers;
    private final Clock clock;

    public SharesController(CreateShare createShare, ReadSharedView sharedView, CallerResolver callers, Clock clock) {
        this.createShare = Objects.requireNonNull(createShare, "createShare");
        this.sharedView = Objects.requireNonNull(sharedView, "sharedView");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A link that shows whoever holds it what the group owes whom, and joins nobody. */
    @PostMapping("/groups/{groupId}/shares")
    public ResponseEntity<Object> create(@PathVariable String groupId, HttpServletRequest http) {
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<CreatedInvite> created = createShare.create(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                new NewShare(UUID.fromString(groupId), clock.instant()));

        if (created instanceof Result.Failure<CreatedInvite> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InviteResponse.of(((Result.Ok<CreatedInvite>) created).value()));
    }

    /** What a share link shows, to whoever holds it: no session, because the code is the credential. */
    @GetMapping("/shared/{code}")
    public ResponseEntity<Object> read(@PathVariable String code, HttpServletRequest http) {
        DomainError malformed = malformedCode(code);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<SharedView> view = sharedView.read(code.strip(), clock.instant());

        if (view instanceof Result.Ok<SharedView> found) {
            return ResponseEntity.ok(SharedViewResponse.of(found.value()));
        }

        return problem(((Result.Failure<SharedView>) view).error(), http.getRequestURI());
    }

    private static DomainError malformedCode(String code) {
        if (code == null || code.isBlank()) {
            return new RunRefusals.RequestIncomplete("code in the path, the share link's code");
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

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}