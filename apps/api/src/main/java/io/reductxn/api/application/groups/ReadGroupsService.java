package io.reductxn.api.application.groups;

import io.reductxn.api.port.in.GroupView;
import io.reductxn.api.port.in.ReadGroups;
import io.reductxn.api.port.out.Groups;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: the groups a person belongs to.
 *
 * <p><strong>{@code readOnly}, and the annotation earns its place here.</strong> The read joins memberships to groups and
 * counts them per group; a transaction that could dirty anything would be claiming a write this use case does not have,
 * and {@code readOnly} is how Spring's neighbours - and a reader - are told which of the two this is (RULE B15).
 *
 * <p>It answers with an empty list for somebody in no group, which is the honest answer rather than a refusal: a person
 * who has just signed in belongs to nothing, and a client that showed an error for that would be shouting at the ordinary
 * first page.
 */
@Service
public class ReadGroupsService implements ReadGroups {

    private final Groups groups;

    public ReadGroupsService(Groups groups) {
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Transactional(readOnly = true)
    public List<GroupView> of(UUID userId) {
        Objects.requireNonNull(userId, "userId");

        return groups.ofMember(userId).stream().map(GroupViews::of).toList();
    }
}
