package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A cycle as this module reports it: the row, plus the plan over it when one exists.
 *
 * <p><strong>One type for both endpoints that describe a cycle.</strong> Opening one and listing them answer about
 * the same resource, and two records with the same five fields would be two descriptions of one thing - the drift
 * ADR 0021 refuses for runs, arriving on the cycle side through the door marked "the write needs a slightly
 * different shape". What the write needs is not a different shape but one extra fact, and that is
 * {@link OpenedCycle}'s to carry.
 *
 * <p><strong>{@code runId} is a fact rather than a status, and it is absent for two different situations.</strong> A
 * cycle the worker has not computed yet and a cycle it refused to plan (nothing recorded at or before {@code asOf},
 * {@code settlement.nothing_to_settle}) are both "no run". Telling them apart would mean recording the refusal -
 * a column, a lifecycle and a judgement this module does not make - so the report says what it knows, and a client
 * shows "asked, waiting since" which is true of both. The run's own status, once it exists, lives on the run.
 *
 * @param id        the cycle's identity
 * @param groupId   whose books it closes
 * @param asOf      the instant they were closed at - the cycle settles as of it
 * @param createdAt when it was opened, which is what a client reports as "waiting since"
 * @param runId     the plan over it, when one exists
 */
public record CycleReport(UUID id, UUID groupId, Instant asOf, Instant createdAt, Optional<UUID> runId) {

    public CycleReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(runId, "runId");
    }
}
