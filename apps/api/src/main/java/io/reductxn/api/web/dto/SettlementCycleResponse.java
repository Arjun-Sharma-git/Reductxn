package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.reductxn.api.port.in.CycleReport;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's `SettlementCycle`: a closed period of a group's books, and the plan over it when one exists.
 *
 * <p>Four facts and no status of its own, mirroring the row in {@code settlement_cycles} rather than adding to it.
 * Whether the cycle has *progressed* is a question about a run - its status, its gates, its figures - and the runs
 * endpoints answer that where those live. What this carries is {@code runId}, which is a fact about the cycle: the
 * plan is either there or it is not.
 *
 * <p><strong>{@code runId} is absent for two situations and the schema does not distinguish them.</strong> A cycle
 * the worker has not computed yet (its cadence runs within a minute) and a cycle it refused to plan, because the
 * group has nothing recorded at or before the instant. Recording the refusal would be a column and a judgement this
 * module does not make, so a client says "asked, waiting since {@code createdAt}" - which is true of both, and a
 * list ordered by when cycles were asked for makes a cycle that waits for days an anomaly a reader can see.
 *
 * <p>{@code createdAt} is what a client shows as "waiting since" rather than the moment the plan appeared: a cycle
 * has no computing instant of its own.
 */
public record SettlementCycleResponse(UUID id, UUID groupId, Instant asOf, Instant createdAt, UUID runId) {

    public static SettlementCycleResponse of(CycleReport cycle) {
        return new SettlementCycleResponse(cycle.id(), cycle.groupId(), cycle.asOf(), cycle.createdAt(),
                cycle.runId().orElse(null));
    }
}
