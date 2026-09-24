package io.reductxn.netting.api;

import io.reductxn.netting.internal.GreedyNetting;

/**
 * Public factories for netting engines.
 *
 * <p>As with tax rules, implementations stay private and callers see only the interface (RULE B10).
 */
public final class NettingEngines {

    private NettingEngines() {
    }

    /**
     * Tier 1 of ADR 0005: the greedy baseline.
     *
     * <p>Always correct, always terminating, and bounded at N-1 transfers for N members with a
     * non-zero position. Every optimiser result must be validated against this, so it is never
     * removed even once the optimiser exists.
     */
    public static NettingEngine greedy() {
        return new GreedyNetting();
    }
}
