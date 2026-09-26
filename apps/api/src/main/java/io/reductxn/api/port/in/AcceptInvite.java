package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: somebody hands in a code and joins the group it is for.
 *
 * <p><strong>Three refusals, and the third is the one this slice exists for.</strong> A code nobody holds is a
 * {@code 410}'s neighbour - a {@code 404}, because the code identifies an invitation and a mistyped one is a code for
 * nothing rather than for the wrong group. A code whose window has closed, and a code that has been spent as often as it
 * allows, are both refusals a reader can act on: they differ in *why* it ran out, which is why they are two codes under
 * the one status the contract lists.
 *
 * <p><strong>Accepting a code for a group that already has you is a success, not a conflict.</strong> That is the
 * decision the coverage entry named, and the answer comes from the members write rather than from this one: a duplicate
 * membership is idempotent there - `(groupId, userId)` is the primary key and the insert does nothing on conflict - so
 * "you are already in this group" is the same answer as "you are in it now" with nothing changed. A {@code 409} would
 * make the most benign case an error, which is what P8 exists to avoid, and it is also why an acceptance that records
 * nothing does not spend a use: an invitation is spent by joining, and nobody joined (ADR 0041).
 *
 * <p>What this use case does <em>not</em> do is check whether the group exists, or whether the person is allowed to
 * join: there is no identity in this build, so there is no admin whose word would decide it, and the body is who the
 * caller says (ADR 0020, 0041).
 */
public interface AcceptInvite {

    /**
     * Joins {@code accepted}'s member to the group the code is for.
     *
     * @return the member as stored, with {@link AddedMember#created()} saying whether this call is what joined them
     */
    Result<AddedMember> accept(AcceptedInvitation accepted);
}
