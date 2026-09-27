package io.reductxn.api.application.balances;

import io.reductxn.api.port.in.InvitationGrant;
import io.reductxn.api.port.in.InviteRefusals;
import io.reductxn.api.port.in.ReadSharedView;
import io.reductxn.api.port.in.SharedView;
import io.reductxn.api.port.out.GroupMembers;
import io.reductxn.api.port.out.Invitations;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: what a share link shows, to whoever holds it.
 *
 * <p><strong>The balances read with a code instead of a membership.</strong> The positions come from the same
 * {@link GroupPositions} the member's read uses - one implementation, two conditions - and this class is what differs:
 * ADR 0054's {@code groups.isMember} is replaced by "this code grants reading and is still in its window". The pairing is
 * deliberate and it is the clearest statement of what this slice decided: the difference between the two reads is *who
 * may ask*, and nothing else.
 *
 * <p><strong>The code's row is read once and answers everything about the code.</strong> {@code Invitations.previewOf}
 * returns what it grants, its group's id and label, how many members the group has and its window - so this read needs no
 * second query to find out which group it is looking at, and no port that answers "what is this group" without a
 * membership (which {@code Groups} deliberately cannot do). Three statements in total: the code and the group's label,
 * the positions, and the names.
 *
 * <p><strong>The names are a second statement, and they are here because a code holder cannot ask for them.</strong>
 * {@code GET /groups/{groupId}/members} is the group's own read (ADR 0054), so the name a reader needs to say "who owes
 * what" has to travel with the position - which is why {@link SharedView.SharedPosition} carries one. An id the
 * membership does not name keeps a null name rather than a guess: the clearing account is not a person, and a member who
 * has left is one the group no longer names.
 *
 * <p><strong>The refusals are ordered by what they are about.</strong> Is there such a code (else `404`); does it grant
 * reading (else `422`, and before the window, because a share link's nature does not change with time); is its window
 * open (else `410`); are its uses spent (else `410`) - which for a code made by this build is unreachable, since a share
 * link has no limit, and is asked anyway because the row is what decides rather than the operation that wrote it.
 *
 * <p>{@code @Transactional(readOnly = true)} for the reason the member's read gives: one view of the rows, so two
 * statements cannot report two instants - and here it also keeps the code and the positions consistent with each other.
 */
@Service
public class ReadSharedViewService implements ReadSharedView {

    private final Invitations invitations;
    private final GroupMembers members;
    private final GroupPositions positions;

    public ReadSharedViewService(Invitations invitations, GroupMembers members, GroupPositions positions) {
        this.invitations = Objects.requireNonNull(invitations, "invitations");
        this.members = Objects.requireNonNull(members, "members");
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<SharedView> read(String code, Instant at) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(at, "at");

        Optional<Invitations.Preview> found = invitations.previewOf(code);

        if (found.isEmpty()) {
            return Result.fail(new InviteRefusals.NoSuchInvite(code));
        }

        Invitations.Preview invite = found.get();

        // Before the window: what a code grants is what it *is*, and no amount of time turns a share link into an
        // invitation or the other way round. The refusal names the true condition rather than pretending the code does
        // not exist, because the person asking holds it (ADR 0055, and InviteRefusals.NotAShare says why).
        if (invite.grants() != InvitationGrant.READ) {
            return Result.fail(new InviteRefusals.NotAShare(code));
        }

        if (!at.isBefore(invite.expiresAt())) {
            return Result.fail(new InviteRefusals.InviteExpired(code));
        }

        if (invite.maxUses() != null && invite.uses() >= invite.maxUses()) {
            return Result.fail(new InviteRefusals.InviteExhausted(code));
        }

        Map<UUID, String> names = namesOf(invite.groupId());

        return Result.ok(new SharedView(
                code,
                invite.expiresAt(),
                at,
                new SharedView.Group(invite.groupId(), invite.groupName(), invite.memberCount()),
                positions.of(invite.groupId(), at).stream()
                        .map(position -> new SharedView.SharedPosition(position, names.get(position.userId())))
                        .toList()));
    }

    /** What the group calls each of its members, by id. Absent for an id the membership does not name. */
    private Map<UUID, String> namesOf(UUID groupId) {
        Map<UUID, String> names = new HashMap<>();

        members.listByGroup(groupId).forEach(member -> names.put(member.userId(), member.displayName()));

        return names;
    }
}