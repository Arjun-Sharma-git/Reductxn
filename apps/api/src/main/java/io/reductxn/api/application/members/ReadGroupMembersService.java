package io.reductxn.api.application.members;

import io.reductxn.api.port.in.GroupMember;
import io.reductxn.api.port.in.ReadGroupMembers;
import io.reductxn.api.port.out.GroupMembers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's members.
 *
 * <p>The thinnest service in this module, and it owns no decision at all - not even the limit the runs and cycles
 * reads clamp, because this list is not paged ({@code ReadGroupMembers.list} says why). What it does own is the
 * transaction: one statement, read-only, which says to the connection what the query is rather than promising an
 * isolation this endpoint never needs.
 *
 * <p>It exists as a use case rather than as a controller calling a repository for the reason RULE B12 gives, and the
 * reason is not ceremony here: the endpoint has been in the contract for several slices with no implementation, and
 * the missing piece was this port-in - the controller could not have called a repository it does not have
 * (ADR 0035).
 */
@Service
public class ReadGroupMembersService implements ReadGroupMembers {

    private final GroupMembers members;

    public ReadGroupMembersService(GroupMembers members) {
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupMember> list(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");

        return members.listByGroup(groupId);
    }
}
