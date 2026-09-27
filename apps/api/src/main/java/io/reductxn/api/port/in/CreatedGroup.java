package io.reductxn.api.port.in;

import java.util.Objects;

/**
 * A group as it now stands, and whether this call is what put it there.
 *
 * <p>{@code created} is what the boundary turns into {@code 201} or {@code 200} - the same shape the obligation, member
 * and invitation writes answer their replays with. Both paths carry the same group, because a replay is not an error: a
 * client retried because it did not know whether the first attempt had landed, and the honest answer is what the record
 * says. A different id on the replay would tell the client a second group exists.
 */
public record CreatedGroup(GroupView group, boolean created) {

    public CreatedGroup {
        Objects.requireNonNull(group, "group");
    }
}
