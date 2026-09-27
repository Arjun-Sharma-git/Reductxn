package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.CreateInvite;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.InvitationGrant;
import io.reductxn.api.port.in.NewInvite;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: make an invitation code, if the caller is in the group it is for.
 *
 * <p><strong>The window and the limit live in {@code InvitationPolicy} since ADR 0055, and that is most of why this
 * class has any code.</strong> The contract's operation takes no request body, so a caller cannot ask for a different
 * window or a different limit - which makes them this deployment's decision, not a default a caller could have
 * overridden and did not. They moved out of this class when the share link started applying the same two rules: one
 * policy, two operations.
 *
 * <p><strong>And since ADR 0048 it is also where "somebody in the group" is decided.</strong> The check is the same
 * {@code groups.isMember} the members write uses and the same {@code no_such_group} refusal, because it is the same fact:
 * a group somebody is not in is not theirs to hand codes out for. Before this, the operation's own description said that
 * "anyone can create an invitation" - which was honest, and is now false, so the description changed with the code
 * rather than after it.
 *
 * <p><strong>No {@code @Transactional}</strong>, and the absence is the reason {@code AddMemberService} gives: this is
 * one insert whose own uniqueness constraint settles every race it can have, so a transaction would say something about
 * isolation that is not true of a single statement.
 *
 * <p><strong>{@code MEMBERSHIP} is written here rather than defaulted in the store</strong> (ADR 0055): the table's
 * default exists so that adding the column to a populated table was one statement, and a creation that relied on it
 * would be a creation whose grant changes the day somebody adds a third word to that default.
 */
@Service
public class CreateInviteService implements CreateInvite {

    private final Invitations invitations;
    private final Groups groups;

    public CreateInviteService(Invitations invitations, Groups groups) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<CreatedInvite> create(UUID callerId, NewInvite invite) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(invite, "invite");

        // Before a code is minted, so a refusal costs nothing and leaves no invitation behind for somebody to find.
        if (!groups.isMember(invite.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(invite.groupId()));
        }

        Instant createdAt = invite.createdAt();

        return Result.ok(invitations.record(
                invite.groupId(),
                InvitationGrant.MEMBERSHIP,
                createdAt,
                createdAt.plus(InvitationPolicy.WINDOW),
                InvitationPolicy.NO_LIMIT));
    }
}
