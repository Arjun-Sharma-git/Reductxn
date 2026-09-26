package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

/**
 * Recording that a run's external currency requirement has been met (RULE S4).
 *
 * <p>The escaping half of {@code awaiting_fx_funding}. Without it the state was a dead end: a run whose
 * clearing position was non-zero could never become fundable, so the gate S4 describes had nothing to open and
 * the transition out of the state existed only in the spec's diagram.
 *
 * <p><strong>Funding moves the run from {@code awaiting_fx_funding} to {@code awaiting_approval}, or leaves it
 * where it is.</strong> Partial funding is recorded and the run stays blocked, which is the honest report: the
 * group has brought in some of what it needs and the report says how much is still outstanding rather than
 * either ignoring the partial arrival or declaring the requirement met.
 */
public interface RecordFxFunding {

    /**
     * Records currency arriving from outside and answers with the run as it now stands.
     *
     * @return the run, with its funding read back from the rows, or a refusal when nothing was requiring this
     */
    Result<ComputedRun> fund(FundingRequest request);
}
