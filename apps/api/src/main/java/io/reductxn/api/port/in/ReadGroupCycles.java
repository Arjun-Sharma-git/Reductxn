package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.List;
import java.util.UUID;

/**
 * The use case: the cycles of one group, newest first.
 *
 * <p><strong>The read that closes ADR 0033's own gap.</strong> Opening a cycle writes a claim about an instant, and
 * whether a plan exists for it is the worker's answer a minute later - so without this list, a member who asked for a
 * settlement and a member whose cycle the worker refused to plan see the same thing, which is nothing. ADR 0030 made
 * a run findable so it could be answered; this makes a cycle findable so it can be waited on, and the two reads are
 * the same shape of honesty about a two-deployable flow.
 *
 * <p><strong>A {@code Result} since ADR 0054.</strong> This port used to say that no {@code Result} was needed
 * because "a group with no cycles and a group that does not exist are both the empty list, and both are true in a
 * build where groups are not tables" - and the clause after the comma is the one that expired: groups have been
 * tables since ADR 0043, so the two cases can be told apart and one of them is not the caller's to read. The caller
 * travels beside the request and the refusal is {@link GroupRefusals.NoSuchGroup}.
 */
public interface ReadGroupCycles {

    /**
     * At most {@code limit} cycles, most recently opened first.
     *
     * @param limit clamped by the caller, because a limit is a boundary concern rather than a rule
     */
    Result<List<CycleReport>> list(UUID callerId, UUID groupId, int limit);
}
