package io.reductxn.api.port.in;

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
 * <p>No {@code Result}, for the reason {@link ReadGroupRuns} gives: a group with no cycles and a group that does not
 * exist are both the empty list, and both are true in a build where groups are not tables.
 */
public interface ReadGroupCycles {

    /**
     * At most {@code limit} cycles, most recently opened first.
     *
     * @param limit clamped by the caller, because a limit is a boundary concern rather than a rule
     */
    List<CycleReport> list(UUID groupId, int limit);
}
