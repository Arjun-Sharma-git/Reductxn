package io.reductxn.api.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An obligation as it now stands on the record: what the use case answers with, whether it recorded it or
 * recognised that it had already been recorded.
 *
 * <p>Both paths answer with the same type on purpose. A replay is not an error and should not be answered
 * with one: the client retried because it did not know whether the first attempt had landed, and the
 * service's job is to tell it what the record says. Returning the same {@code id} is what makes the retry
 * safe rather than merely repeatable - it is the identity every entry id was derived from
 * ({@code libs/booking}), so a client that received a different id would have been told a second commitment
 * exists.
 *
 * <p>{@code status} is read from the row rather than assumed, which is what lets a replay report
 * {@link ObligationStatus#VOIDED} for an obligation that has since been voided. A newly recorded obligation
 * is always {@link ObligationStatus#OPEN}.
 *
 * @param settleInCurrency what the creditor is to receive. Never null: it equals {@code amount}'s currency
 *                         for an ordinary obligation, and stating it is a claim about the commitment
 *                         rather than an echo of the request
 */
public record RecordedObligation(
        UUID id,
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        Money amount,
        Currency settleInCurrency,
        String memo,
        ObligationStatus status,
        Instant occurredAt) {

    public RecordedObligation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(settleInCurrency, "settleInCurrency");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
