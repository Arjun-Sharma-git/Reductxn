package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A settlement run as it stands on the record.
 *
 * <p>No amounts. A run's numbers are per currency and per participant, and they live in its plan - the
 * partitions, positions and transfers written when it was computed. A run carrying a total would be the one
 * thing this product must never report, because adding yen to rupees is not addition (ADR 0012 point 4), and
 * a field called {@code total} invites exactly that.
 *
 * @param id         the run's identity, derived from its cycle so that a retry collides rather than duplicating
 * @param groupId    the group whose commitments this settles
 * @param cycleId    the cycle being settled: a {@code (group, as-of)} pair
 * @param status     where in the lifecycle it stands
 * @param asOf       the instant the cycle is cut off at, which is also the instant every rate, tax rule and
 *                   obligation was resolved at
 * @param computedAt when the plan was computed
 * @param createdAt  when the run was opened - the same instant today, because computing is synchronous; the
 *                   spec says it will not stay that way, so the two are separate columns
 */
public record SettlementRun(
        UUID id,
        UUID groupId,
        UUID cycleId,
        RunStatus status,
        Instant asOf,
        Instant computedAt,
        Instant createdAt) {

    public SettlementRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(cycleId, "cycleId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(computedAt, "computedAt");
        Objects.requireNonNull(createdAt, "createdAt");

        if (computedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "a run cannot have computed at " + computedAt + " before it was created at " + createdAt);
        }
    }

    public boolean requiresExternalFunding() {
        return status == RunStatus.AWAITING_FX_FUNDING;
    }

    /**
     * The same run, in another state.
     *
     * <p>A copy rather than a rebuilt record, so a status change cannot quietly alter when the run was computed
     * or which cycle it belongs to. The use case moves a run between states; it does not get to reconstruct one.
     */
    public SettlementRun withStatus(RunStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        return new SettlementRun(id, groupId, cycleId, newStatus, asOf, computedAt, createdAt);
    }
}
