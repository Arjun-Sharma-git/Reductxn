package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;

/**
 * How a jurisdiction taxes a single transfer, and what that implies for netting.
 *
 * <p>Principle P10: tax law is configuration, not code. A jurisdiction's rule is data resolved by
 * {@code (jurisdiction, currency, effective date)}; implementations of this interface are the
 * small, fixed set of <em>shapes</em> such a rule can take.
 *
 * <p>The {@link #objective()} is what makes tax and netting one module rather than two: the
 * optimiser needs to know whether to minimise the number of transfers or the volume transferred.
 */
public interface TaxRule {

    /**
     * Computes the tax on one transfer.
     *
     * @param base the amount being transferred, a non-negative magnitude
     * @return the tax, in the same currency as the base
     */
    Money assess(Money base);

    /** What the netting optimiser should minimise when this rule is active. */
    Objective objective();

    /** The optimisation objective implied by a tax shape. */
    enum Objective {

        /** A fixed charge per transfer: minimise the number of transfers. */
        MIN_TRANSFERS,

        /** A percentage of the amount: minimise the volume transferred. */
        MIN_VOLUME
    }
}
