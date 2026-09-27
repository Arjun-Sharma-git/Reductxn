package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * Opening a settlement cycle: a member saying that a group's books are closed at an instant.
 *
 * <p><strong>It answers a {@code Result} since ADR 0049, and the paragraph that used to say it could not is worth
 * keeping in mind.</strong> This interface said the absence was a claim: everything else the module writes can be
 * refused for a reason a client can act on, while opening a cycle could be refused for nothing - the pair is the
 * table's only constraint and a second request for a pair is the same cycle again. What that argument missed is the
 * caller: it reasoned about the *row* being unrerefusable and not about who was asking for it. Closing a group's books
 * is an act within the group, so a caller who is not a member is refused - and the shape had to change to carry that.
 *
 * <p><strong>What it does not do is compute.</strong> A plan over the cycle is {@code workers/settlement}'s work
 * (ADR 0032), and its cadence computes an opened cycle within a minute. This port writes the fact that a settlement
 * is wanted, which is the part that a policy - a group's schedule, a member, an operator - can supply.
 */
public interface OpenCycle {

    /**
     * Opens the cycle for this group and instant, or finds the one that is already open for them.
     *
     * @param callerId who is asking, from the boundary's session - the condition this use case gained in ADR 0049
     * @return the cycle as stored, with {@link OpenedCycle#created()} saying whether this call opened it, or
     *         {@code GroupRefusals.NoSuchGroup} when the caller is not a member of the group
     */
    Result<OpenedCycle> open(UUID callerId, NewCycle request);
}
