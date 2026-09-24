package io.reductxn.ledger.api;

import java.util.Objects;

/**
 * An exact monetary amount: an integer number of minor units plus a currency.
 *
 * <p>Principle P1: reductxn never represents money as a binary floating-point value. The amount is
 * a {@code long} count of minor units (paise, cents, ...) and the currency supplies the exponent
 * needed to render it. Every operation here is exact, and overflow throws rather than wrapping.
 *
 * @param amountMinor the amount in minor units; the sign carries direction
 * @param currency    the currency this amount is denominated in
 */
public record Money(long amountMinor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money of(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    /** Magnitude, used when reporting a position without its direction. */
    public Money absolute() {
        return amountMinor < 0 ? negate() : this;
    }

    public Money scaledBy(long factor) {
        return new Money(Math.multiplyExact(amountMinor, factor), currency);
    }

    /**
     * A percentage of this amount expressed in basis points, rounded half-up to the nearest minor
     * unit. Basis points keep the arithmetic integral: 250 bps is 2.5 percent.
     *
     * <p>Used by ad valorem tax rules. The base must not be negative; pass a magnitude.
     */
    public Money percentageBps(long rateBps) {
        if (amountMinor < 0) {
            throw new IllegalArgumentException("percentageBps requires a non-negative base");
        }
        if (rateBps < 0) {
            throw new IllegalArgumentException("rateBps must not be negative: " + rateBps);
        }
        long product = Math.multiplyExact(amountMinor, rateBps);
        return new Money(Math.addExact(product, 5_000L) / 10_000L, currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    /** Rendered for display, delegating to the currency's exponent. */
    public String format() {
        return currency.format(amountMinor);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return format() + " " + currency.code();
    }
}
