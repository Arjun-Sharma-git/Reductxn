package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: a member asks for an approved run to be offered to the rail now.
 *
 * <p><strong>It is a request rather than an execution, and that is the whole shape of this operation.</strong> The
 * thing that moves money is {@code workers/settlement}'s {@code ExecuteRun}, reached by its driver, and this module
 * cannot call it - so all this use case does is put the member's intent where the driver reads it, which is
 * {@code settlement_runs.execute_requested_at} (ADR 0050). The two deployables communicate the way they always have:
 * a row one writes and the other reads.
 *
 * <p><strong>So the failures here are about the request, never about the money.</strong> A run that does not exist, or
 * one whose group the caller is not in, is refused with the codes every other run and group read refuses with. A run
 * that cannot make progress is answered {@code 409} with the status it is in, which is the contract's own answer - and
 * the readiness is *projected* from what the worker wrote down ({@code settlement_run_gates}) rather than recomputed
 * here, because quorum is arithmetic this module may not derive (ADR 0021, 0023).
 *
 * <p>What it deliberately does not refuse is a run waiting on currency: the driver will honour the request as soon as
 * the group brings the currency in, which is a request worth recording rather than a request worth refusing. The
 * contract's {@code 409} names quorum and "already executing", and this follows it rather than widening it.
 */
public interface RequestRunExecution {

    /**
     * Records the caller's request, if the caller is a member of the run's group and the run can still move.
     *
     * @param callerId who is asking, from the boundary's session
     * @param runId    the run to execute now
     * @return what was recorded, or the reason the request was refused
     */
    Result<RequestedExecution> request(UUID callerId, UUID runId);
}
