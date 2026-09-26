package io.reductxn.api.port.out;

import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An obligation about to be written: everything the table needs, and nothing the row decides.
 *
 * <p>No {@code status}, because a row's status is its {@code voided_at} and an obligation that has been
 * written has never been voided. A status field here would be a parameter that is always the same value -
 * the complaint {@code Bookings.singleCurrency} makes about a flag that is always false - and it would be
 * the second place that status could come from.
 *
 * @param id             the commitment's identity, chosen by the use case so the entries derive from it
 * @param amount         already resolved to a {@code Currency}, which means the exponent came from the
 *                       catalogue rather than from the request
 * @param quoteId        the locked conversion, for a commitment paid in another currency; null when the
 *                       creditor is paid in the debt currency. The settlement currency is deliberately not a
 *                       field: it is the quote's quote currency, and a second copy could disagree with it
 * @param idempotencyKey the key the header carried; the unique index is on (group, key)
 */
public record ObligationToRecord(
        UUID id,
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        Money amount,
        UUID quoteId,
        String memo,
        String idempotencyKey,
        Instant incurredAt) {

    public ObligationToRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(incurredAt, "incurredAt");
    }
}
