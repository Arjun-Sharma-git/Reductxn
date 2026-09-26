package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * Submitting an approved run's transfers to a rail (RULE S1, S2, S3, S4).
 *
 * <p>This is the transition the whole module exists for, and it is the first thing in reductxn that moves
 * money. Everything before it produces an answer; this produces a movement, and it is deliberately the hardest
 * thing here to write by accident.
 *
 * <p><strong>Every gate is checked before anything moves.</strong> Quorum (S1), the funding requirement (S4)
 * and the rail's own declared currencies are all evaluated against the run's stored rows first. A run that
 * fails any of them is refused with its transfers untouched, because the alternative - discovering the problem
 * halfway through a submission loop - is exactly the partial application RULE S3 makes a first-class outcome
 * for reasons outside anyone's control, and it should not be reachable for reasons inside it.
 *
 * <p><strong>It is idempotent, and the idempotency is structural rather than a flag.</strong> Each transfer
 * carries a reference derived from its own coordinates (S2), and a second call skips what is resolved, polls
 * the rail for what it has already accepted, and only submits what the rail has never seen. Executing a run
 * that is {@code done} is therefore a read that returns the same run, not an error and not a second movement.
 *
 * <p><strong>Failure is partial, and is never rolled back.</strong> If some transfers confirm and others fail
 * terminally the run is {@code failed}: the confirmed ones stay confirmed, the failed ones stay failed with the
 * rail's reason, and the shortfall is the group's to see (S3). There is no compensating transaction, because
 * money that has moved cannot be unmoved - and replaying the run is safe, so a group can fix the cause and ask
 * again.
 *
 * <p><strong>Nothing here is one database transaction, and that is the point.</strong> The rail is outside the
 * database: a single transaction spanning the whole pass would hold a connection open across every network
 * call, and a rollback after a transfer confirmed would erase the only record that the money moved. So each
 * state change is committed on its own, before the call it describes - section 4.2 of the spec, which is a
 * sentence about crash recovery rather than about style.
 */
public interface ExecuteRun {

    /**
     * Executes the run, or resumes one that is part-way through.
     *
     * @param runId the run to execute
     * @return the run as it now stands, with every transfer's state read back from the rows, or a refusal
     */
    Result<ComputedRun> execute(UUID runId);
}
