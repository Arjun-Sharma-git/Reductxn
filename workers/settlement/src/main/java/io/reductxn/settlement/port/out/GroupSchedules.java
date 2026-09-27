package io.reductxn.settlement.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group's schedule, as much of it as the worker acts on (ADR 0057).
 *
 * <p><strong>Two statements, and they are the two halves of one guarantee.</strong> {@code dueBefore} asks which groups
 * said their books close at or before an instant; {@code advance} moves one of them on by its interval. The pair is what
 * makes the cadence exactly-once in practice: the first is a read that can return the same row to two passes, and the
 * second is a conditional write that only one of them can win - so a second worker, or a retry, opens nothing twice.
 *
 * <p><strong>{@code advance} is conditional rather than absolute, and that is the whole point.</strong> It sets
 * {@code next_due_at} to the new value only if the row still holds the instant that was read. An unconditional update
 * would let two passes that read the same row both advance it, which loses a period silently - the failure that would be
 * hardest to explain to a group whose cycle never happened.
 */
public interface GroupSchedules {

    /**
     * The schedules whose next cycle is due at or before {@code dueBefore}, soonest first.
     *
     * @param batchSize the most rows to return, so a backlog drains over several passes rather than in one long one
     */
    List<DueSchedule> dueBefore(Instant dueBefore, int batchSize);

    /**
     * Moves a schedule on, if it still says what was read.
     *
     * @param expectedDueAt the instant the caller read; the update applies only while the row still holds it
     * @param nextDueAt     what to set it to
     * @return true when this call moved it, false when another pass got there first - which is not a failure, and is the
     *     ordinary answer to a retry
     */
    boolean advance(UUID groupId, Instant expectedDueAt, Instant nextDueAt);

    /** One due schedule: which group, when its next closure is due, and how often after that. */
    record DueSchedule(UUID groupId, Instant nextDueAt, int everyDays) {

        public DueSchedule {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(nextDueAt, "nextDueAt");
        }
    }
}