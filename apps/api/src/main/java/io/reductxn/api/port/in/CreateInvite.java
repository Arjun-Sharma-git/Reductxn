package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: make a code that lets somebody join a group, if the caller is in that group.
 *
 * <p><strong>It answers a {@code Result} since ADR 0048, and it is the same condition the members write gained.</strong>
 * The store still cannot refuse - a code collision is settled by the primary key and retried, and a group nobody has
 * joined yet is a perfectly ordinary group, because groups are not tables in this build (ADR 0035) - but the *caller* can:
 * an invitation is a link a group hands to somebody it trusts, so only somebody in the group may create one. Before this,
 * any caller could mint a code for any group id they could type, which the contract said out loud rather than hiding.
 *
 * <p><strong>It exists at all because a read with no writer is a dead end, and here the writer comes first.</strong>
 * `POST /groups/{groupId}/invites` has been in the contract since the obligations slice with nothing behind it, and the
 * table it needs did not exist until this slice. The acceptance endpoint is its other half, and the two share one rule:
 * a code is usable until it expires or runs out of uses, and the only way anybody gets one is through this operation.
 *
 * <p>The window and the limit are this use case's decision rather than a caller's, because the contract gives the
 * operation no request body to put them in. That is stated in the contract's description with the values, so a reader
 * knows what they are getting rather than inferring it from behaviour.
 */
public interface CreateInvite {

    /**
     * Records an invitation to {@code invite}'s group and answers with the code it can be spent by.
     *
     * @param callerId who is asking, from the boundary's session - the condition this use case gained in ADR 0048
     * @return the code, or {@code GroupRefusals.NoSuchGroup} when the caller is not a member of the group
     */
    Result<CreatedInvite> create(UUID callerId, NewInvite invite);
}
