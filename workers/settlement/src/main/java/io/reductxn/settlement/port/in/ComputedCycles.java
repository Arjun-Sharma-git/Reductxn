package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one pass of the computer did, cycle by cycle.
 *
 * <p>A report rather than a count, for the reason {@link DrivenRuns} gives: "three cycles were considered" hides
 * the three things an operator needs to know - one became a run awaiting approval, one group had nothing to settle
 * at that instant, and one failed in a way somebody has to look at.
 *
 * <p><strong>Three outcomes rather than two, because a refusal and a fault are different facts.</strong> A refusal
 * is this module answering the request it was given ({@code settlement.nothing_to_settle}: the group had no open
 * commitment at that instant) and it needs no attention. A fault is a plan that could not be computed at all, and
 * the pass reports it rather than propagating it - one cycle's fault must not stop the rest of the batch, and it
 * must not be silent either.
 *
 * @param outcomes one entry per cycle attempted, in the order they were attempted
 */
public record ComputedCycles(List<CycleOutcome> outcomes) {

    public ComputedCycles {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * One cycle's attempt.
     *
     * @param cycleId the cycle that was opened
     * @param groupId whose book it is, so a report can be read without a second lookup
     * @param asOf    the instant it was opened for, which is what the run was computed over
     * @param runId   the run it became, or null when no plan was recorded
     * @param status  where that run stands - {@code awaiting_approval} or {@code awaiting_fx_funding} - or null
     *                when there is no run
     * @param refusal why this module refused, as {@code code: message}, or null
     * @param fault   what went wrong, as {@code class: message}, or null. Never both this and {@code refusal}
     */
    public record CycleOutcome(
            UUID cycleId, UUID groupId, Instant asOf, UUID runId, RunStatus status,
            String refusal, String fault) {

        /** Whether this attempt produced a run. The only outcome that moved anything. */
        public boolean computed() {
            return runId != null;
        }

        /** Whether the module answered "there is nothing to settle here", which is an answer rather than a fault. */
        public boolean refused() {
            return refusal != null;
        }

        /** Whether a plan could not be computed at all, which is the outcome an operator has to look at. */
        public boolean faulted() {
            return fault != null;
        }
    }

    /** How many cycles became runs, as opposed to being refused or faulting. */
    public int computed() {
        return (int) outcomes.stream().filter(CycleOutcome::computed).count();
    }

    /** How many were answered with a refusal: a group with nothing to settle at that instant. */
    public int refused() {
        return (int) outcomes.stream().filter(CycleOutcome::refused).count();
    }

    /** How many could not be attempted at all. */
    public int faulted() {
        return (int) outcomes.stream().filter(CycleOutcome::faulted).count();
    }
}
