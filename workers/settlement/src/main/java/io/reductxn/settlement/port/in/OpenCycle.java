package io.reductxn.settlement.port.in;

/**
 * Opening a cycle: saying that a settlement is wanted, without saying what it is.
 *
 * <p><strong>Why this is separate from {@link ComputeRun}.</strong> They were one act until this port existed -
 * computing a cycle wrote the cycle and the run in one transaction - and that made a group's settlement policy
 * impossible to express: what a policy decides is <em>which instant the books are closed at</em>, and the plan is
 * then a consequence of that instant. An operator who has to compute in order to open cannot close a period and
 * settle it later, and nothing can be scheduled, because there is no state that means "wanted, not yet computed".
 *
 * <p><strong>The instant is the whole request.</strong> {@link RunRequest} is reused rather than given a twin
 * because it is the same pair: the cycle is keyed by {@code (group, as-of)}, so an open and a compute for the same
 * two values are about the same cycle - which is exactly the property that lets one path open a cycle that the
 * other later computes.
 *
 * <p><strong>Nothing here decides that a cycle has come due.</strong> That is a group's schedule, a group's
 * property, and there is no group schedule in this build (the plan records it as an open question and this port
 * forecloses none of the answers). What exists now is the mechanism a policy needs: something that can say
 * "as of this instant", a row that records it, and a cadence that computes what was recorded.
 */
public interface OpenCycle {

    /**
     * Opens the cycle for this group and instant, if it is not already open.
     *
     * @return true when this call opened it, false when it was already open - which is not a failure, and is the
     *         ordinary answer to a retry
     */
    boolean open(RunRequest request);
}
