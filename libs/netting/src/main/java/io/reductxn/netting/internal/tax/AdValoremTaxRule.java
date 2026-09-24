package io.reductxn.netting.internal.tax;

import io.reductxn.ledger.api.CurrencyMismatchException;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.TaxRule;

import java.util.Objects;

/**
 * A percentage of the transferred amount, optionally floored and capped.
 *
 * <p>The rate is expressed in basis points so the arithmetic stays integral: 250 bps is 2.5 percent.
 * Here the number of transfers matters far less than the volume moved, which is why the optimiser
 * objective is {@code MIN_VOLUME}.
 *
 * <p>The floor is deliberately not applied to a zero base, so a zero transfer always incurs zero
 * tax. Without that, a tax rule could manufacture a charge out of nothing.
 *
 * @param rateBps basis points, 0 to 10,000
 * @param minimum the smallest charge, in the same currency as the base
 * @param maximum the largest charge, in the same currency as the base
 */
public record AdValoremTaxRule(long rateBps, Money minimum, Money maximum) implements TaxRule {

    public AdValoremTaxRule {
        if (rateBps < 0 || rateBps > 10_000L) {
            throw new IllegalArgumentException("rateBps must be between 0 and 10000: " + rateBps);
        }
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (!minimum.currency().equals(maximum.currency())) {
            throw new CurrencyMismatchException(minimum.currency(), maximum.currency());
        }
        if (minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
    }

    @Override
    public Money assess(Money base) {
        Objects.requireNonNull(base, "base");
        if (base.isNegative()) {
            throw new IllegalArgumentException("a tax base must be a magnitude and must not be negative");
        }
        if (!minimum.currency().equals(base.currency())) {
            throw new CurrencyMismatchException(minimum.currency(), base.currency());
        }
        if (base.isZero()) {
            return Money.zero(base.currency());
        }

        Money assessed = base.percentageBps(rateBps);
        Money floored = assessed.compareTo(minimum) < 0 ? minimum : assessed;
        return floored.compareTo(maximum) > 0 ? maximum : floored;
    }

    @Override
    public Objective objective() {
        return Objective.MIN_VOLUME;
    }
}
