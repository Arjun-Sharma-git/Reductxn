package io.reductxn.api.port.out;

import io.reductxn.api.port.in.CycleSchedule;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A group's schedule, as stored (ADR 0057).
 *
 * <p>Three statements, and each is one thing a caller means: write it (`put`), read it, remove it. The write is an
 * upsert rather than an insert-then-update pair, because a group has one schedule and "set" and "change" are the same
 * command - which is what the contract's {@code PUT} says and what the table's key settles.
 *
 * <p><strong>{@code setAt} is a parameter rather than a column default.</strong> It is the boundary's clock (RULE B21),
 * so a test that pins the instant gets a reproducible row, and a deployment whose database clock differs from its
 * application clock cannot make a schedule look older or newer than the moment it was stated.
 */
public interface GroupSchedules {

    /** Writes the schedule, replacing whatever this group had. Answers with what it wrote, stamp included. */
    CycleSchedule put(UUID groupId, int everyDays, Instant nextDueAt, Instant setAt);

    /** The group's schedule, or empty when it has none - which is a state the read answers {@code 404} for. */
    Optional<CycleSchedule> read(UUID groupId);

    /**
     * Removes the group's schedule.
     *
     * @return true when a row was removed, false when there was none - which the use case turns into a refusal rather
     *     than answering the same success to both
     */
    boolean clear(UUID groupId);
}