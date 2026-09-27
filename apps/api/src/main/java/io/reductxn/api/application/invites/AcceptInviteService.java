package io.reductxn.api.application.invites;

import io.reductxn.api.port.in.AcceptInvite;
import io.reductxn.api.port.in.AcceptedInvitation;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.Invitation;
import io.reductxn.api.port.in.InvitationGrant;
import io.reductxn.api.port.in.InviteRefusals;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.out.GroupMembers;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The use case: somebody hands in a code and joins the group it is for.
 *
 * <p><strong>This is the transaction in this slice, and RULE B15 is why.</strong> {@code AddMemberService} needs no
 * boundary - one statement settles its own races - but an acceptance is two writes with a promise between them: a use is
 * spent *because* a membership is about to be recorded. If the membership write failed after the spend, an invitation
 * would have been used up by nobody. Both are therefore in one transaction, and the promise is the annotation's.
 *
 * <p><strong>The order of the four questions is the slice's decision.</strong> Does the code exist (else `404`); is the
 * window open (else "expired"); is this person already a member (then there is nothing to do and nothing to spend);
 * and only then spend a use, where the database decides whether there was one left. Asking about the membership before
 * spending is what keeps the benign case benign: somebody who is already in the group does not burn an invitation that
 * somebody else then needs, and the only race left - the same person accepting twice at the same instant - costs at
 * most one of their own extra uses (see {@code GroupMembers.has}).
 *
 * <p><strong>When the spend fails, the refusal is read rather than inferred.</strong> The row is fetched again to ask
 * which condition has stopped it, so a caller is never told "exhausted" about an invitation that merely expired while
 * they were being accepted. One extra read on a refusal path buys a refusal that names the truth.
 *
 * <p>Accepting joins as {@link #MEMBER_ROLE}. The invitation carries no role, and an invitation that conferred `admin`
 * would be a privilege handed out by a default rather than decided by somebody.
 */
@Service
public class AcceptInviteService implements AcceptInvite {

    /** What accepting an invitation makes you. The contract says so, and there is nothing here to say otherwise. */
    static final String MEMBER_ROLE = "member";

    private final Invitations invitations;
    private final GroupMembers members;

    public AcceptInviteService(Invitations invitations, GroupMembers members) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    @Transactional
    public Result<AddedMember> accept(AcceptedInvitation accepted) {
        Objects.requireNonNull(accepted, "accepted");

        String code = accepted.code();
        Instant at = accepted.acceptedAt();

        Optional<Invitation> found = invitations.findByCode(code);

        if (found.isEmpty()) {
            return Result.fail(new InviteRefusals.NoSuchInvite(code));
        }

        Invitation invite = found.get();

        // Second, because it is the other thing about the code's *nature* rather than about its state: a share link does
        // not join anybody, and no window makes it able to (ADR 0055). Answering 422 rather than 404 is deliberate - the
        // refusal names the true condition because the caller already holds the code.
        if (invite.grants() != InvitationGrant.MEMBERSHIP) {
            return Result.fail(new InviteRefusals.NotAMembership(code));
        }

        // Half-open, like every window in this codebase: an acceptance exactly at the expiry instant is too late, and
        // the instant one invitation ends is the instant the next one could begin.
        if (!at.isBefore(invite.expiresAt())) {
            return Result.fail(new InviteRefusals.InviteExpired(code));
        }

        NewMember joining = new NewMember(invite.groupId(), accepted.userId(), accepted.displayName(), MEMBER_ROLE, at);

        if (members.has(invite.groupId(), accepted.userId())) {
            // Already one of the group's members: the membership write is idempotent and answers with the row as it
            // stands, so this is a success that changed nothing - and nothing is why no use is spent.
            return Result.ok(members.add(joining));
        }

        if (!invitations.spend(code, at)) {
            return Result.fail(whySpendingFailed(code, at));
        }

        return Result.ok(members.add(joining));
    }

    /** The condition that actually stopped the spend, asked of the row rather than assumed from the read before it. */
    private DomainError whySpendingFailed(String code, Instant at) {
        boolean expired = invitations.findByCode(code)
                .map(invite -> !at.isBefore(invite.expiresAt()))
                .orElse(false);

        return expired ? new InviteRefusals.InviteExpired(code) : new InviteRefusals.InviteExhausted(code);
    }
}
