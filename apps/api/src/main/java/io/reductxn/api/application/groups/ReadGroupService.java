package io.reductxn.api.application.groups;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.GroupView;
import io.reductxn.api.port.in.ReadGroup;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: one group, if the caller is in it.
 *
 * <p><strong>The refusal is one code for two conditions, and the sameness is a security decision.</strong> "No group with
 * that id" and "not a group you are in" are answered identically because telling them apart would let anybody holding a
 * guess learn which ids exist - the same reasoning that makes an unknown phone number and a wrong code one answer on the
 * sign-in path (ADR 0042). The store does the narrowing, so there is no window in which this service could answer about a
 * group the caller cannot see.
 */
@Service
public class ReadGroupService implements ReadGroup {

    private final Groups groups;

    public ReadGroupService(Groups groups) {
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<GroupView> read(UUID groupId, UUID userId) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");

        Optional<Groups.StoredGroup> group = groups.forMember(groupId, userId);

        return group
                .map(found -> Result.ok(GroupViews.of(found)))
                .orElseGet(() -> Result.fail(new GroupRefusals.NoSuchGroup(groupId)));
    }
}
