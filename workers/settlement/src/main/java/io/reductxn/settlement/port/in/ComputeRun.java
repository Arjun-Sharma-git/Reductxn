package io.reductxn.settlement.port.in;

import io.reductxn.kernel.api.Result;

/**
 * Opening and computing a run for a group's cycle.
 *
 * <p>This is the worker's whole inbound surface today. There is no controller behind it: the module has no HTTP
 * surface by design, so nothing triggers a run yet - the tests call this directly and a scheduler is a later
 * slice. Stating that plainly matters more than it looks, because a service that nothing calls is
 * indistinguishable from a service that works, and the difference is a caller.
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
