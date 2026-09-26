package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.CreateInvite;
import io.reductxn.api.port.in.CreatedInvite;
import io.reductxn.api.port.in.NewInvite;
import io.reductxn.api.port.out.Invitations;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The use case: make an invitation code.
 *
 * <p><strong>This class is where the window and the limit live, and that is the whole reason it has any code.</strong>
 * The contract's operation takes no request body, so a caller cannot ask for a different window or a different limit -
 * which makes them this deployment's decision, not a default that a caller could have overridden and did not. Seven days
 * and no use limit: an invitation is a link a group shares with somebody it trusts, and what stops it is time rather than
 * a count. A group that wants a one-time code needs an operation to ask for one, which is a decision somebody has to make
 * on purpose (ADR 0041).
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

    public CreateInviteService(Invitations invitations) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
    }

    @Override
    public CreatedInvite create(NewInvite invite) {
        Objects.requireNonNull(invite, "invite");

        Instant createdAt = invite.createdAt();

        return invitations.record(invite.groupId(), createdAt, createdAt.plus(WINDOW), NO_LIMIT);
    }
}
