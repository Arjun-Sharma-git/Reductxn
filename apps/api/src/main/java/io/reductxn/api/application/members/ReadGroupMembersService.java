package io.reductxn.api.application.members;

import io.reductxn.api.port.in.GroupMember;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadGroupMembers;
import io.reductxn.api.port.out.GroupMembers;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's members.
 *
 * <p>The thinnest service in this module, and it owns no decision but the membership check - not even the limit the
 * runs and cycles reads clamp, because this list is not paged ({@code ReadGroupMembers.list} says why). What it does
 * own beside the check is the transaction: one statement, read-only, which says to the connection what the query is
 * rather than promising an isolation this endpoint never needs.
 *
 * <p>It exists as a use case rather than as a controller calling a repository for the reason RULE B12 gives, and the
 * reason is not ceremony here: the endpoint has been in the contract for several slices with no implementation, and
 * the missing piece was this port-in - the controller could not have called a repository it does not have
 * (ADR 0035).
 *
 * <p><strong>The check is the read's whole condition since ADR 0054, and the ordering is the point.</strong> This is
 * the read that names people: the group's membership is a list of who is in it and what the group calls them, so the
 * answer a stranger gets is `404` rather than the list, and the store is not asked for the rows at all. It is the
 * same argument {@code ReadActivityService} makes at length, and the two reads are now the same shape - one check,
 * then one read.
 */
@Service
public class ReadGroupMembersService implements ReadGroupMembers {

    private final Groups groups;
    private final GroupMembers members;

    public ReadGroupMembersService(Groups groups, GroupMembers members) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.members = Objects.requireNonNull(members, "members");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<GroupMember>> list(UUID callerId, UUID groupId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");

        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        return Result.ok(members.listByGroup(groupId));
    }
}
