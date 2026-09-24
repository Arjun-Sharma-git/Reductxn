package io.reductxn.netting.internal.tax;

import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.TaxRule;

import java.util.Objects;

/**
 * A fixed charge per transfer, independent of the amount moved.
 *
 * <p>This is the shape that makes netting maximally valuable: the total tax is the per-transfer
 * charge multiplied by the number of transfers, so removing transfers removes tax one-for-one.
 * It is also the reason the optimiser objective is {@code MIN_TRANSFERS}.
 *
 * @param perTransfer the charge applied to each transfer
 */
public record FlatPerTransferTaxRule(Money perTransfer) implements TaxRule {

    public FlatPerTransferTaxRule {
        Objects.requireNonNull(perTransfer, "perTransfer");
        if (perTransfer.isNegative()) {
            throw new IllegalArgumentException("a per-transfer charge must not be negative");
        }
    }

    @Override
    public Money assess(Money base) {
        Objects.requireNonNull(base, "base");
        if (base.isZero()) {
            return Money.zero(base.currency());
        }
        if (!perTransfer.currency().equals(base.currency())) {
            throw new CurrencyMismatchException(perTransfer.currency(), base.currency());
        }
        return perTransfer;
    }

    @Override
    public Objective objective() {
        return Objective.MIN_TRANSFERS;
    }
}
