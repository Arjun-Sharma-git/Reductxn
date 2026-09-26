package io.reductxn.api.port.in;

/**
 * Opening a settlement cycle: a member saying that a group's books are closed at an instant.
 *
 * <p><strong>No {@code Result}, and the absence is a claim.</strong> Everything else this module writes can be
 * refused for a reason a client can act on - a run that does not exist, an answer already given, a currency with no
 * rate - so those return {@link io.reductxn.kernel.api.Result}. Opening a cycle can be refused for nothing: the
 * table's only other constraint is the pair, and a second request for a pair is not a failure but the same cycle
 * again. A {@code Result} with one reachable branch would be a shape that promises a decision nobody makes, which is
 * the fiction this repository keeps removing.
 *
 * <p><strong>What it does not do is compute.</strong> A plan over the cycle is {@code workers/settlement}'s work
 * (ADR 0032), and its cadence computes an opened cycle within a minute. This port writes the fact that a settlement
 * is wanted, which is the part that a policy - a group's schedule, a member, an operator - can supply.
 */
public interface OpenCycle {

    /**
     * Opens the cycle for this group and instant, or finds the one that is already open for them.
     *
     * @return the cycle as stored, with {@link OpenedCycle#created()} saying whether this call opened it
     */
    OpenedCycle open(NewCycle request);
}
