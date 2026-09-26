package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: read a settlement run.
 *
 * <p><strong>It computes nothing, and that is the decision this member of the port exists to embody.</strong> A run's
 * rows already say what the plan is, what the rail is being asked to move, and who has answered; the questions a
 * read model could be tempted to answer - is quorum met, what does the group still owe from outside, what is the
 * tax saving in the reporting currency - are *judgements over* those rows, and they are made in the module that owns
 * the run ({@code workers/settlement}). An API that recomputed them would be a second implementation of the run's
 * arithmetic, drifting from the first the day either changed, which is the drift ADR 0020 refuses for an answer and
 * ADR 0021 refuses for a projection.
 *
 * <p>What the reader does get is the state machine's own answer to "what is happening": the run's status, which the
 * worker moves when its gates are satisfied. {@code awaiting_approval} and {@code awaiting_fx_funding} are
 * different blockers, and telling them apart is the useful granularity - who still has to answer, and whether a
 * currency still has to arrive, are derived from that state and the rows, not from a second quorum implementation.
 *
 * <p>A {@link Result} like every other use case here: a run that does not exist is an expected failure, not a fault.
 */
public interface ReadRun {

    /**
     * The run, its per-currency figures, its transfers and its answers - one read, one view of the rows.
     *
     * @return the run as it now stands, or the reason it could not be read
     */
    Result<RunReport> read(UUID runId);
}
