package io.reductxn.api.port.out;

import io.reductxn.planning.api.CycleObligation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The obligations a preview starts from: everything still open in a group at a given instant.
 *
 * <p>A port rather than a query, because the preview must be answerable without the HTTP layer knowing
 * anything about storage, and because the alternative - a controller reading a table - is the specific
 * way this architecture dies (RULE B12, and MASTER-PLAN says so in those words).
 *
 * <p><strong>Why an instant rather than "now".</strong> The contract's preview takes {@code asOf}, so a
 * member can ask what a cycle would have cost at a past moment. An implementation that read the clock
 * itself could not answer that, and could not be tested without one.
 *
 * <p>Returns {@link CycleObligation}, the sealed type the partitioner consumes, so an implementation
 * cannot hand back a shape the planner would have to reinterpret - a cross-currency obligation arrives
 * with its locked quote or it arrives as the wrong type.
 */
public interface OpenObligations {

    /**
     * Every obligation in this group that is still open at {@code asOf}.
     *
     * @return the obligations, in any order - the planner normalises ordering itself
     */
    List<CycleObligation> openFor(UUID groupId, Instant asOf);
}
