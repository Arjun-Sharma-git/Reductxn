package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

/**
 * Opening and computing a run for a group's cycle.
 *
 * <p>This is the worker's whole inbound surface for a plan, and where it is called from has changed once: for several
 * slices nothing called it at all - the tests did - and the honest thing to write down then was that a service
 * nothing calls is indistinguishable from a service that works, and the difference is a caller. There are now three,
 * and they are three different answers to "who asked for this settlement": an operator's command
 * ({@code --reductxn.command=compute}), the cadence that computes cycles somebody opened
 * ({@link ComputeOpenedCycles}, which is what makes a settlement policy expressible - ADR 0032), and a retry of
 * either, which finds the run it already opened.
 *
 * <p><strong>Idempotent on {@code (group, as-of)}.</strong> Opening a run twice for the same cycle is a read of
 * the first, not a second plan: the cycle is keyed by that pair, the run is keyed by the cycle, and both ids are
 * derived from it. A retry after a timeout is the ordinary case this is for - and a second plan for one cycle
 * would be a second answer to a question the group has already been asked.
 */
public interface ComputeRun {

    /**
     * Computes the plan for the group's cycle and records it.
     *
     * @return the run and its per-currency outcome, or a refusal when there is nothing to settle
     */
    Result<ComputedRun> compute(RunRequest request);
}
