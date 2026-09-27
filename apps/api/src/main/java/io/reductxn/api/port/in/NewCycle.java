package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A group's books closed at an instant, as {@code POST /v1/groups/{groupId}/cycles} carries it.
 *
 * <p><strong>The instant is the request.</strong> It keys the cycle, it is what the worker resolves every rate and
 * tax rule at, and it is the one thing a settlement policy decides (ADR 0032). There is no period and no action:
 * the domain has no notion of a period - a cycle is a claim about a moment, and "the month of September" is a
 * client's way of choosing one - and closing a cycle has nowhere to be stored, because {@code settlement_cycles}
 * has no status column.
 *
 * <p><strong>This module does not judge whether there is anything to settle, and that is deliberate.</strong> The
 * group's commitments are this module's rows, so the answer is computable here, and computing it would be wrong: a
 * commitment recorded tomorrow can carry an instant inside this cycle - a backdated bill is the ordinary case - so
 * "nothing to settle as of that instant" is not a fact about the request, only about the moment it was asked. The
 * refusal belongs to the worker that computes the plan
 * ({@code settlement.nothing_to_settle}), and a {@code 422} here would refuse a request that worker would satisfy a
 * minute later.
 *
 * <p>{@code requestedAt} is non-null while the wire does not carry it, because the boundary is the only layer
 * allowed to read a clock (RULE B21): the controller substitutes "now", and everything below receives an instant.
 * The group is named in the path because it is the resource being closed, and the caller travels beside this record
 * rather than inside it ({@link OpenCycle#open}) - a caller is who is asking rather than what is being asked. That
 * caller must be a member of the group since ADR 0049, which is what this paragraph used to defer to "the identity
 * slice".
 *
 * @param groupId     the group whose books are being closed
 * @param asOf        the instant the cycle is cut off at
 * @param requestedAt when the request arrived, read from the clock at the boundary
 */
public record NewCycle(UUID groupId, Instant asOf, Instant requestedAt) {

    public NewCycle {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
