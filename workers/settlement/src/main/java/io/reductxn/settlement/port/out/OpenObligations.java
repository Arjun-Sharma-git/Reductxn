package io.reductxn.settlement.port.out;

import io.reductxn.planning.api.CycleObligation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The obligations a run starts from: everything still open in a group at the cycle's instant.
 *
 * <p>A read model over {@code obligations}, which is {@code apps/api}'s table (RULE B17). It is a read model
 * <em>rather than</em> a dependency on that module, and that is the whole reason it exists: this worker and
 * {@code apps/api} may never depend on each other (RULE B6), so each reads the other's tables through its own
 * projection. The API will do the same in the other direction over these tables when it reports a run.
 *
 * <p>That does mean the reconstruction of a row into a {@link CycleObligation} exists twice - here and in the
 * API's preview. Stated rather than glossed: it is what a read model is, the two are projections of one table
 * rather than two models of one concept, and the disagreement it could hide is visible in the one place it
 * matters - both are reading the same rows with the same guard against a cross-currency obligation whose quote
 * is missing.
 *
 * <p>The semantics are deliberately identical to the preview's port of the same name, including {@code asOf}
 * meaning "open at that instant" rather than "created before it": a run and a preview of the same cycle must be
 * the same answer, or a group would approve one thing and execute another.
 */
public interface OpenObligations {

    /**
     * Every obligation in this group that is still open at {@code asOf}.
     *
     * @return the obligations, in any order - the planner normalises ordering itself
     */
    List<CycleObligation> openFor(UUID groupId, Instant asOf);
}
