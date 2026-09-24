package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * A single real transfer that must happen to settle a group.
 *
 * <p>This is the output the group reads as "who pays whom". Each instruction carries the tax
 * assessed on it, because the tax is what netting exists to minimise.
 *
 * @param fromUserId the payer
 * @param toUserId   the payee
 * @param amount     exact amount in minor units
 * @param tax        tax assessed on this specific transfer
 */
public record TransferInstruction(UUID fromUserId, UUID toUserId, Money amount, Money tax) {

    public TransferInstruction {
        Objects.requireNonNull(fromUserId, "fromUserId");
        Objects.requireNonNull(toUserId, "toUserId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(tax, "tax");
        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("a transfer must move money between two different members");
        }
        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("a transfer amount must be strictly positive");
        }
        if (tax.isNegative()) {
            throw new IllegalArgumentException("tax must not be negative");
        }
    }
}
