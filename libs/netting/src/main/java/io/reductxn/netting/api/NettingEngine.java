package io.reductxn.netting.api;

/**
 * Nets a group's obligations into the minimum-transfer settlement.
 *
 * <p>Implementations must be pure, deterministic, and thread-safe: the same request always yields
 * the same result, and no implementation may perform I/O. That is what allows an auditor to
 * reproduce any historical settlement without a database.
 */
public interface NettingEngine {

    /**
     * @param request a validated request
     * @return the netted positions and the transfers that settle them
     */
    NettingResult net(NettingRequest request);
}
