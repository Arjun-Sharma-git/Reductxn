package io.reductxn.api.application.members;

import io.reductxn.api.port.in.AddMember;
import io.reductxn.api.port.in.AddedMember;
import io.reductxn.api.port.in.NewMember;
import io.reductxn.api.port.out.GroupMembers;

import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * The use case: record a member of a group.
 *
 * <p><strong>No {@code @Transactional}, and the absence is the point.</strong> The store's write is
 * {@code ON CONFLICT DO NOTHING} followed by a read of the row the pair identifies, so the statement settles every
 * race the uniqueness constraint can produce and the answer is whichever row exists when it is read. Wrapping it in
 * its own transaction would add nothing to a single-statement write and would say something about isolation that is
 * not true of the deployment (RULE B15 keeps the annotation where a boundary is actually needed, and
 * {@code RecordDecisionService} is the one that needs it).
 */
@Service
public class AddMemberService implements AddMember {

    private final GroupMembers members;

    public AddMemberService(GroupMembers members) {
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    public AddedMember add(NewMember member) {
        Objects.requireNonNull(member, "member");

        return members.add(member);
    }
}
