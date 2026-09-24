package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * The netting engine's view of a commitment: who owes whom, and how much.
 *
 * <p>The engine deliberately does not know about the persisted obligation aggregate. Callers map
 * their aggregate onto this record, which keeps the engine pure and independently testable
 * (principle P4).
 *
 * @param debtorId   the member who owes
 * @param creditorId the member who is owed
 * @param amount     a positive magnitude; direction is expressed by the two member ids
 */
public record Obligation(UUID debtorId, UUID creditorId, Money amount) {

    public Obligation {
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(amount, "amount");
        if (debtorId.equals(creditorId)) {
            throw new IllegalArgumentException("debtor and creditor must be different members");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException("an obligation amount is a magnitude and must not be negative");
        }
    }
}
