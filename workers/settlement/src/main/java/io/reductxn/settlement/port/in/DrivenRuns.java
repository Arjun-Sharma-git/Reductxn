package io.reductxn.settlement.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one pass of the driver did, run by run.
 *
 * <p>A report rather than a count, because the interesting answer is not "three runs were considered" but "one
 * settled, one is unfunded in JPY, and one is waiting on a group's approval". Those three lines are the
 * operator's whole view of a worker that is doing its job quietly, and collapsing them into a number would make
 * a stuck run look like a working one.
 *
 * @param attemptedBefore the cut-off the pass used, so the report says which runs it could have seen
 * @param outcomes        one entry per run attempted, in the order they were attempted
 */
public record DrivenRuns(Instant attemptedBefore, List<RunOutcome> outcomes) {

    public DrivenRuns {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * One run's attempt.
     *
     * @param runId   the run
     * @param groupId whose book it is, so a report can be read without a second lookup
     * @param status  where the run is now - after the attempt when it progressed, and where it stood when it
     *                was refused otherwise
     * @param reason  why nothing happened, as {@code code: message}, or null when the run was driven
     */
    public record RunOutcome(UUID runId, UUID groupId, RunStatus status, String reason) {

        /** Whether this attempt moved the run at all. A refusal is an answer, and it is not progress. */
        public boolean driven() {
            return reason == null;
        }
    }

    /** How many runs were driven, as opposed to refused by a gate that is waiting on somebody. */
    public int driven() {
        return (int) outcomes.stream().filter(RunOutcome::driven).count();
    }

    /** How many runs were left where they were, with the reason each gave. */
    public int refused() {
        return outcomes.size() - driven();
    }

    /** Runs that ended this pass, so a cadence's log line can say whether anything finished. */
    public List<RunOutcome> completed() {
        return outcomes.stream().filter(outcome -> outcome.status() == RunStatus.DONE).toList();
    }

    /** Runs that failed this pass, which is the outcome an operator has to look at. */
    public List<RunOutcome> failed() {
        return outcomes.stream().filter(outcome -> outcome.status() == RunStatus.FAILED).toList();
    }
}
