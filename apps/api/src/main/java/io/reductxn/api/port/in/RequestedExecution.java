package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's request for a run's transfers to be offered to the rail, as it was written down.
 *
 * <p><strong>It is a receipt, not a status.</strong> Nothing has moved when this is returned: the run is offered to a
 * rail by {@code workers/settlement}'s driver, which reads {@code execute_requested_at} on its next pass, and the two
 * deployables do not depend on each other (RULE B6, ADR 0020). So this is what the API knows - that the request is now
 * on the record, who asked and when - and it says nothing about whether the money moved. What the money did is on the
 * run, where {@code GET /runs/{runId}} and the group's runs already serve it.
 *
 * @param runId       the run that was asked for
 * @param requestedBy the member who asked, from the session
 * @param requestedAt when the request was recorded, from the boundary's clock (RULE B21)
 */
public record RequestedExecution(UUID runId, UUID requestedBy, Instant requestedAt) {

    public RequestedExecution {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }
}
