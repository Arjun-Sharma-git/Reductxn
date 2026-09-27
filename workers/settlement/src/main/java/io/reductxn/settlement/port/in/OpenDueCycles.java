package io.reductxn.settlement.port.in;

/**
 * The use case: which cycles a group's schedule has brought due, and what each attempt became (ADR 0057).
 *
 * <p><strong>This is the front of the pipeline the worker already had.</strong> {@code ComputeOpenedCycles} computes runs
 * for cycles that have been opened and have none; the driver then settles them. Until this port existed, nothing in the
 * deployment opened a cycle except a person - {@code OpenCycle} said so out loud ("nothing here decides that a cycle has
 * come due ... there is no group schedule in this build") - so a group's periods were whatever somebody remembered to
 * ask for.
 *
 * <p><strong>The instant opened is the schedule's, not the worker's.</strong> A cycle is opened at exactly the
 * {@code nextDueAt} the group stated, so a worker that was down when a period fell due opens it late but dated correctly
 * - and the run computed for it resolves the obligations at that instant rather than at the moment somebody noticed
 * (ADR 0032's rule that the instant is the whole request).
 */
public interface OpenDueCycles {

    /**
     * Opens a cycle for every schedule whose next closure is due at or before {@code dueBefore}.
     *
     * @param dueBefore  the instant to judge "due" against, supplied by the caller rather than read here (RULE B21)
     * @param batchSize  the most schedules to act on in one pass
     * @return one outcome per schedule that was due, in the order the schedules were read
     */
    OpenedCycles openDue(java.time.Instant dueBefore, int batchSize);
}