package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A membership to record: who, what to call them, and what role the group gives them.
 *
 * <p>{@code joinedAt} arrives from the boundary, which is where \"now\" is decided (RULE B21) - a use case that read
 * its own clock could not be tested at a fixed instant, and this one is read back by a list ordered on that instant.
 *
 * @param groupId     the group the person is a member of
 * @param userId      the person
 * @param displayName what the group calls them
 * @param role        {@code admin} or {@code member}, checked at the boundary against {@link MemberRefusals#ROLES}
 * @param joinedAt    when the membership was recorded
 */
public record NewMember(UUID groupId, UUID userId, String displayName, String role, Instant joinedAt) {

    public NewMember {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(joinedAt, "joinedAt");
    }
}
