package io.reductxn.api.port.in;

import java.util.Objects;
import java.util.UUID;

/**
 * A member of a group, as this module reports one: what to call them and what the group calls them.
 *
 * <p><strong>Two facts and no policy.</strong> There is no credit limit here, which the contract's schema used to
 * promise: nothing in this build sets one or consults one, and a field that is always absent is a promise a client
 * waits for (ADR 0035). There is also no membership test, because the question \"is this person in the group\" is a
 * lookup rather than a list - and the write that needs it (recording an obligation that names somebody) is where it
 * will be asked, with its own refusal.
 *
 * <p>{@code displayName} is what the group recorded rather than a directory lookup: there is no {@code users} table
 * in this build, so a name is a fact the group agreed on. {@code role} is likewise recorded and nothing consults it -
 * a build without identity has nobody whose {@code admin} row would weigh more than a member's (ADR 0035).
 *
 * @param userId      the person, as the group's own record identifies them
 * @param displayName what the group calls them
 * @param role        {@code admin} or {@code member}, as the schema's own vocabulary; recorded, not enforced
 */
public record GroupMember(UUID userId, String displayName, String role) {

    public GroupMember {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(role, "role");
    }
}
