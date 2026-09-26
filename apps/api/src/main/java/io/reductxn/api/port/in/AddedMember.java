package io.reductxn.api.port.in;

import java.util.Objects;

/**
 * The member a request produced, and whether this request is what recorded them.
 *
 * <p>{@code created} is in the value rather than in the boundary's choice of status, for the reason
 * {@link OpenedCycle} gives: only the store knows whether an insert happened or found a row, so it says so, and the
 * controller answers {@code 201} for a membership this request recorded and {@code 200} for one the group already
 * had. The alternative - asking \"is this person a member?\" before writing - is the check-then-insert that loses the
 * race a unique constraint exists to settle.
 *
 * <p>The member itself is a {@link GroupMember} rather than a second record with the same fields: what the write
 * knows that the read does not is one boolean, and one boolean is what it adds.
 */
public record AddedMember(GroupMember member, boolean created) {

    public AddedMember {
        Objects.requireNonNull(member, "member");
    }
}
