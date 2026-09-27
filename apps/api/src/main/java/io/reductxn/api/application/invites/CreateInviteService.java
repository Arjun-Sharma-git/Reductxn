package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.CreateInvite;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.NewInvite;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: make an invitation code, if the caller is in the group it is for.
 *
 * <p><strong>This class is where the window and the limit live, and that is most of why it has any code.</strong>
 * The contract's operation takes no request body, so a caller cannot ask for a different window or a different limit -
 * which makes them this deployment's decision, not a default that a caller could have overridden and did not. Seven days
 * and no use limit: an invitation is a link a group shares with somebody it trusts, and what stops it is time rather than
 * a count. A group that wants a one-time code needs an operation to ask for one, which is a decision somebody has to make
 * on purpose (ADR 0041).
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
 * <p>The {@code long} constant is deliberately not a property. A window that a deployment can change is a window whose
 * expiry nobody can reproduce: a test asserting "seven days" would be asserting whatever the properties file said, and an
 * invitation created before a configuration change would have an expiry that cannot be explained from the code. When a
 * caller needs to choose, the contract gains a body and this becomes a parameter.
 */
@Service
public class CreateInviteService implements CreateInvite {

    /** How long an invitation lasts. See the class comment for why this is not configuration. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** No limit on how many times it may be spent: what stops an invitation is time. */
    static final Integer NO_LIMIT = null;

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

        return Result.ok(invitations.record(invite.groupId(), createdAt, createdAt.plus(WINDOW), NO_LIMIT));
    }
}
