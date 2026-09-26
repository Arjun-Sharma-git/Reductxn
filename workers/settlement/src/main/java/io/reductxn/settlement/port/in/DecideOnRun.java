package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

/**
 * Recording a member's answer on a computed run (RULE S1).
 *
 * <p>This is the run's quorum half. It does not execute anything and it does not decide whether the run has
 * quorum - the run's stored approvals do, and the answer is read back through {@link Quorum#met()} on the run
 * that is returned. Metering quorum here and again at execution would be two derivations of one rule.
 *
 * <p><strong>A rejection moves the run to {@code rejected}; an approval moves nothing on its own.</strong> The
 * lifecycle draws {@code awaiting_approval --> executing} as one transition, and this is the half of it that
 * does not happen here: the last approval makes quorum <em>true</em>, and execution is what enters
 * {@code executing} - because a run that entered it on the last approval would be claiming the worker had
 * begun submitting transfers, which it had not. The same choice keeps a rejection meaningful: it is a state the
 * run is in, not the absence of a state.
 */
public interface DecideOnRun {

    /**
     * Records the decision and answers with the run as it now stands.
     *
     * @return the run, with its approvals, quorum and funding read back from the rows, or a refusal
     */
    Result<ComputedRun> decide(RunDecision decision);
}
