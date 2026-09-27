package io.reductxn.api.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * The write behind {@code POST /runs/{runId}/execute}: a member's request, into the run's own row.
 *
 * <p><strong>This module writes a column it does not own, and that is the cost ADR 0020 documents rather than an
 * oversight.</strong> The table is {@code workers/settlement}'s and the column exists for this: the API is the HTTP
 * surface a member reaches, the worker owns the rail, and the two deployables do not depend on each other - so the
 * intent travels as a row, exactly as {@code settlement_cycles} carries "close the books at this instant" from this
 * module to the cadence that computes it (ADR 0050).
 *
 * <p>It is one column and one instant, and it carries no state machine: the request is a flag the driver spends by
 * attempting the run, and the attribution beside it is a record rather than a second flag. Nothing here decides
 * whether the run may move - that is the worker's, in {@code ExecuteRun}, and this port is deliberately incapable of
 * it.
 */
public interface RunExecutionRequests {

    /**
     * Records that this member asked for this run to be executed.
     *
     * <p>Written last, after every refusal has been decided, and never read back: a request that was recorded is a
     * request the next driver pass will consider, and a second request for the same run is the same request again
     * rather than a second movement - which is what makes this operation idempotent.
     *
     * @param runId       the run to mark
     * @param requestedBy the member who asked
     * @param requestedAt when the request arrived, from the boundary's clock
     */
    void record(UUID runId, UUID requestedBy, Instant requestedAt);
}
