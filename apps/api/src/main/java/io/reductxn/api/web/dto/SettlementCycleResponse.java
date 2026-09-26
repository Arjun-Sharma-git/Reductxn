package io.reductxn.api.web.dto;

import io.reductxn.api.port.in.OpenedCycle;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `SettlementCycle`: a closed period of a group's books.
 *
 * <p>Four facts and no status, mirroring the row in {@code settlement_cycles} rather than adding to it. Whether the
 * cycle has been computed, and what became of it, is a question about a *run* - and the worker answers that where it
 * owns the answer, in the group's runs. A {@code status} field here would be this deployable's guess at another
 * module's lifecycle, and it would be wrong in the window between a cycle being opened and its plan existing, which
 * is a window a client is asking about precisely because it is watching it.
 *
 * <p>{@code createdAt} is what a client shows as "waiting since". That is the honest way to report a cycle the
 * worker has not computed yet: the alternative - this API deciding whether the group has anything to settle - would
 * refuse a request the worker can satisfy later, because a commitment recorded tomorrow may carry an instant inside
 * this cycle.
 */
public record SettlementCycleResponse(UUID id, UUID groupId, Instant asOf, Instant createdAt) {

    public static SettlementCycleResponse of(OpenedCycle cycle) {
        return new SettlementCycleResponse(cycle.id(), cycle.groupId(), cycle.asOf(), cycle.createdAt());
    }
}
