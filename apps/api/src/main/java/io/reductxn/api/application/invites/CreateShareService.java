package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.CreateShare;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.InvitationGrant;
import io.reductxn.api.port.in.NewShare;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: make a share link, if the caller is in the group it shows.
 *
 * <p><strong>It is {@code CreateInviteService} with one word different, and the repetition is the decision.</strong>
 * The check, the window and the limit are the same policy ({@code InvitationPolicy}, {@code Groups.isMember}), and the
 * only thing this class decides is that the code it mints grants {@link InvitationGrant#READ}. That looks like a
 * candidate for one operation with a body - and it is exactly what ADR 0055 refused: a body would make "join somebody"
 * a default a caller could forget to change, on an operation whose whole point is that a group chooses which of two
 * audiences it is handing a link to. Two operations, two use cases, one row shape.
 *
 * <p><strong>A member may make one, and that is ADR 0048's rule rather than a new one.</strong> Who may hand a link out
 * is a question about the role word, and the role word is still unread - recorded as a decision in ADR 0056 rather
 * than guessed at here.
 *
 * <p>No {@code @Transactional}, for the reason its sibling gives: one insert, whose uniqueness constraint is what
 * settles every race it can have.
 */
@Service
public class CreateShareService implements CreateShare {

    private final Invitations invitations;
    private final Groups groups;

    public CreateShareService(Invitations invitations, Groups groups) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<CreatedInvite> create(UUID callerId, NewShare share) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(share, "share");

        // Before a code is minted, so a refusal costs nothing and leaves no link behind for somebody to find.
        if (!groups.isMember(share.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(share.groupId()));
        }

        Instant createdAt = share.createdAt();

        return Result.ok(invitations.record(
                share.groupId(),
                InvitationGrant.READ,
                createdAt,
                createdAt.plus(InvitationPolicy.WINDOW),
                InvitationPolicy.NO_LIMIT));
    }
}