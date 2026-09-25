package io.reductxn.settlement.api;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * A commitment whose creditor is paid in a different currency from the debt (ADR 0012 point 2).
 *
 * <p>Contributes **two** legs, in two partitions, with {@code fx_clearing} as the counterparty:
 *
 * <pre>
 *   debt book:     debtor    owes  fx_clearing   owed()
 *   settle book:   fx_clearing owes creditor     settlementAmount()
 * </pre>
 *
 * <p>The two legs use different amounts by design. The debtor's side is exact, because that is what
 * they agreed to pay. The creditor's side is the converted amount, because that is what they agreed to
 * receive. Nothing here reconciles them: the difference is the rounding residual, which is sub-minor
 * and is booked by the ledger, not by a plan that can only express whole transfers (ADR 0013 point 3).
 *
 * @param debtorId   the member who owes
 * @param creditorId the member who is owed
 * @param quote      the locked conversion, carrying the rate, the residual and the audit id
 */
public record CrossCurrencyObligation(UUID debtorId, UUID creditorId, FxQuote quote)
        implements CycleObligation {

    public CrossCurrencyObligation {
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(quote, "quote");

        if (debtorId.equals(creditorId)) {
            throw new IllegalArgumentException("debtor and creditor must be different participants");
        }
        if (FxClearing.isClearing(debtorId) || FxClearing.isClearing(creditorId)) {
            throw new IllegalArgumentException(
                    "fx_clearing is not a member; it cannot be party to a cycle obligation");
        }
        // Direction is expressed by the two member ids, so a negative amount has no meaning here and
        // would silently turn a debt into a credit.
        if (quote.baseAmount().isNegative()) {
            throw new IllegalArgumentException(
                    "an obligation amount is a magnitude and must not be negative");
        }
    }

    @Override
    public Money owed() {
        return quote.baseAmount();
    }

    @Override
    public Currency settlementCurrency() {
        return quote.convertedAmount().currency();
    }

    @Override
    public Money settlementAmount() {
        return quote.convertedAmount();
    }

    /** The debt currency, which is {@code fx_clearing}'s credit side. */
    public Currency debtCurrency() {
        return quote.baseAmount().currency();
    }
}
