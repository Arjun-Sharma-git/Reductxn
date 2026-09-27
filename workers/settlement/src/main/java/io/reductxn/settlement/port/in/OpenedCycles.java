package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one pass over the due schedules did (ADR 0057).
 *
 * <p><strong>One outcome per schedule rather than two counts.</strong> A log line that says "3 opened, 4 skipped" cannot
 * answer the question an operator actually has - which group, which period, and why not - and the worker's convention is
 * to report what it attempted rather than to summarise it (the driver's {@code DrivenRuns} says the same thing for the
 * same reason). The report is what the pass logs and what a test asserts on.
 *
 * @param outcomes what each due schedule became, in the order the schedules were read
 */
public record OpenedCycles(List<Outcome> outcomes) {

    public OpenedCycles {
        outcomes = List.copyOf(outcomes);
    }

    /** How many cycles this pass opened. Distinct from the outcome count, because a loss to another pass opens nothing. */
    public int opened() {
        return (int) outcomes.stream().filter(Outcome::opened).count();
    }

    /**
     * One schedule's fate in one pass.
     *
     * @param groupId   the group whose books the schedule closes
     * @param dueAt     the instant the cycle was opened at - the schedule's own, not the worker's
     * @param nextDueAt what the schedule now says, whether or not this pass was the one that moved it
     * @param opened    true when this pass opened the cycle, false when the cycle was already open
     * @param advanced  true when this pass moved the schedule on, false when another pass got there first
     */
    public record Outcome(UUID groupId, Instant dueAt, Instant nextDueAt, boolean opened, boolean advanced) {

        public Outcome {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(dueAt, "dueAt");
            Objects.requireNonNull(nextDueAt, "nextDueAt");
        }
    }
}