package io.reductxn.settlement.api;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * A commitment owed and settled in the same currency: the ordinary case.
 *
 * <p>Contributes one obligation to one partition, with the debtor and creditor as they are.
 *
 * @param debtorId   the member who owes
 * @param creditorId the member who is owed
 * @param amount     a positive magnitude; direction is expressed by the two member ids
 */
public record SingleCurrencyObligation(UUID debtorId, UUID creditorId, Money amount)
        implements CycleObligation {

    public SingleCurrencyObligation {
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(amount, "amount");

        if (debtorId.equals(creditorId)) {
            throw new IllegalArgumentException("debtor and creditor must be different participants");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                    "an obligation amount is a magnitude and must not be negative");
        }
        // Refused here rather than left to produce a partition in which the clearing account appears to
        // be a member. A group cannot owe itself through the internal account.
        if (FxClearing.isClearing(debtorId) || FxClearing.isClearing(creditorId)) {
            throw new IllegalArgumentException(
                    "fx_clearing is not a member; it cannot be party to a cycle obligation");
        }
    }

    @Override
    public Money owed() {
        return amount;
    }

    @Override
    public Currency settlementCurrency() {
        return amount.currency();
    }

    @Override
    public Money settlementAmount() {
        return amount;
    }
}
