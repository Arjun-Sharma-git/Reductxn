package io.reductxn.ledger.api;

import java.util.Objects;
import java.util.UUID;

/**
 * One side of a double-entry entry: an account, a direction, and a strictly positive amount.
 *
 * <p><strong>Invariant L2: the amount is a magnitude and {@link Direction} carries the sign.</strong> A
 * posting therefore cannot be ambiguous about which side it belongs to, and a zero-amount posting cannot
 * exist at all - it would balance for free, which is how a missing posting hides inside an entry that
 * still appears to balance.
 *
 * <p>The guard throws {@link IllegalArgumentException} rather than carrying an error code. That is
 * deliberate: a code is contract surface (P5, RULE B20), and this failure cannot be caused or acted on
 * by a client - it means the code that built the entry is wrong. Giving it a code would widen the
 * contract to describe a bug.
 *
 * @param accountId the account posted to
 * @param direction debit or credit; never inferred from a sign
 * @param amount    a strictly positive magnitude, in the currency of its {@link Money}
 * @see JournalEntry
 */
public record Posting(UUID accountId, Direction direction, Money amount) {

    public Posting {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a posting amount is a magnitude and must be strictly positive (L2), but was "
                            + amount.amountMinor() + " " + amount.currency().code());
        }
    }

    /**
     * This posting's contribution to a net position: {@code +amount} for a debit, {@code -amount} for a
     * credit.
     *
     * <p>Positive-is-debit is the convention the whole ledger uses. What that means economically depends
     * on the account's type - a debit increases an asset and decreases a liability - which is why
     * {@link Balances} reports a net debit and leaves the interpretation to the caller rather than
     * inventing an account type it does not own.
     */
    public Money signedAmount() {
        return direction == Direction.DEBIT ? amount : amount.negate();
    }

    /** The currency posted in, which is the addressable character of an entry's currency (L6). */
    public Currency currency() {
        return amount.currency();
    }
}
