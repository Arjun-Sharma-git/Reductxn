package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A cycle as it now stands in the worker's rows, and whether this request is what put it there.
 *
 * <p><strong>{@code created} is in the value rather than in the boundary's choice of status.</strong> The store is
 * the only place that can know whether an insert happened or found a row, so it reports it, and the controller
 * answers {@code 201} for a cycle this request opened and {@code 200} for one that was already open. The
 * alternative - the boundary asking "does this cycle exist?" before writing - is the check-then-insert that loses
 * the race a unique constraint exists to settle.
 *
 * <p>It carries no status and no run id. Whether a cycle has been computed and what became of it is a question
 * about a *run*, which is {@code GET /groups/{groupId}/runs}' to answer: a cycle is four facts about a moment, and
 * a {@code status} field here would be this module's guess at another module's lifecycle.
 *
 * @param id        the cycle's identity, minted here rather than derived from the pair
 * @param groupId   whose books it closes
 * @param asOf      the instant they were closed at
 * @param createdAt when this cycle was opened - the first request's instant, for a repeat
 * @param created   whether this request opened it, as opposed to finding it already open
 */
public record OpenedCycle(UUID id, UUID groupId, Instant asOf, Instant createdAt, boolean created) {

    public OpenedCycle {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
