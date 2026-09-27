package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.List;
import java.util.UUID;

/**
 * The use case: the members of one group, in the order they joined.
 *
 * <p><strong>This read had been promised by the contract and missing from the build for several slices</strong>, and
 * the cost was invisible to every suite: the client's member catalogue calls it (three screens name people, and the
 * obligations form cannot pick a party without it), the client's tests stub it, and this module had never asked for
 * members - so the endpoint was a promise with nothing behind it, and in development every screen that names a person
 * fell back to a uuid (ADR 0035).
 *
 * <p><strong>A {@code Result} since ADR 0054, and the name is why the rule matters here as much as anywhere.</strong>
 * This is the read that names people, and the port used to justify serving it to anybody holding an id on the ground
 * that "a group with no members and a group that does not exist are both the empty list, and both are true in a build
 * where groups are not tables" - the second clause expired with ADR 0043. The caller travels beside the request and
 * the refusal is {@link GroupRefusals.NoSuchGroup}.
 */
public interface ReadGroupMembers {

    /**
     * Every member of the group, in the order they joined.
     *
     * <p><strong>No limit, and that is a decision rather than an omission.</strong> The other list reads take one
     * because a run and a cycle accumulate: a group that has settled for five years has thousands of both, and a page
     * of the newest is the useful question. Membership is the opposite shape - it is bounded by how many people are in
     * the group, every screen that names anybody needs the whole set, and a page of it would be a catalogue that
     * silently lacks the name a member is looking for.
     */
    Result<List<GroupMember>> list(UUID callerId, UUID groupId);
}
