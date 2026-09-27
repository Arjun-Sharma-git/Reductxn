package io.reductxn.api.application.members;

import io.reductxn.api.port.in.AddMember;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.out.GroupMembers;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * The use case: record a member of a group, if the caller is one of them.
 *
 * <p><strong>The condition, and why it is neither the store's nor the controller's.</strong> {@code groups.isMember} is
 * asked here, before the write, because adding somebody to a group is an act *within* that group - the rule belongs to
 * the use case, and a controller that checked it would leave the next caller of this port free to skip it (ADR 0045's
 * argument, one boundary further on). What the store does not gain is a check: its insert is still
 * {@code ON CONFLICT DO NOTHING}, and it still cannot refuse anything.
 *
 * <p><strong>The refusal is {@code no_such_group}, not a new code.</strong> "Not in this group" and "no such group" are
 * one answer on purpose, so a stranger holding a guessed id cannot learn whether it is real by comparing two refusals -
 * the same reading the activity feed and the void use (ADR 0044, 0045). A new code here would be a second name for the
 * same fact, which RULE B20 exists to prevent.
 *
 * <p><strong>No {@code @Transactional}, and the absence is the point.</strong> The store's write is
 * {@code ON CONFLICT DO NOTHING} followed by a read of the row the pair identifies, so the statement settles every
 * race the uniqueness constraint can produce and the answer is whichever row exists when it is read. The membership read
 * does not change that: a group whose last member left between the check and the write is not a state this build can
 * reach, because nothing removes a membership. Wrapping it in a transaction would say something about isolation that is
 * not true of the deployment (RULE B15 keeps the annotation where a boundary is actually needed, and
 * {@code RecordDecisionService} is the one that needs it).
 */
@Service
public class AddMemberService implements AddMember {

    private final GroupMembers members;
    private final Groups groups;

    public AddMemberService(GroupMembers members, Groups groups) {
        this.members = Objects.requireNonNull(members, "members");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<AddedMember> add(UUID callerId, NewMember member) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(member, "member");

        if (!groups.isMember(member.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(member.groupId()));
        }

        return Result.ok(members.add(member));
    }
}
