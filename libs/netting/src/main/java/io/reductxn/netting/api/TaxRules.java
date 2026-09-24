package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;
import io.reductxn.netting.internal.tax.AdValoremTaxRule;
import io.reductxn.netting.internal.tax.FlatPerTransferTaxRule;
import io.reductxn.netting.internal.tax.NoTaxRule;

/**
 * Public factories for tax rules.
 *
 * <p>The rule shapes live in {@code internal} so they cannot be referenced directly from outside
 * this module (RULE B10). Callers obtain them here, which keeps the implementation details private
 * while the {@link TaxRule} contract stays public.
 */
public final class TaxRules {

    private TaxRules() {
    }

    /** No tax. The default for record-only deployments and for previews. */
    public static TaxRule none() {
        return NoTaxRule.INSTANCE;
    }

    /**
     * A fixed charge per transfer, regardless of amount.
     *
     * <p>This shape makes the number of transfers the only thing that matters, which is the
     * strongest possible argument for netting.
     */
    public static TaxRule flatPerTransfer(Money perTransfer) {
        return new FlatPerTransferTaxRule(perTransfer);
    }

    /**
     * A percentage of the transferred amount, in basis points, optionally floored and capped.
     *
     * <p>250 bps is 2.5 percent. The floor is not applied to a zero base, so a zero transfer
     * always incurs zero tax.
     *
     * @param rateBps basis points, 0 to 10,000
     * @param minimum the smallest charge, in the same currency
     * @param maximum the largest charge, in the same currency
     */
    public static TaxRule adValoremBps(long rateBps, Money minimum, Money maximum) {
        return new AdValoremTaxRule(rateBps, minimum, maximum);
    }
}
