package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A commitment a member is asking to record, as the contract's request body describes it.
 *
 * <p><strong>Carries a currency code, not a {@code Currency}.</strong> The wire format is
 * {@code { amountMinor, currency }} and the exponent is not in it, so this is what the boundary actually
 * receives - and resolving the exponent is a step the use case performs against the catalogue, not
 * something the controller should quietly do on the way past. Passing a {@code Currency} in would mean
 * either the controller or the mapper had already decided what an INR is.
 *
 * <p>{@code occurredAt} is non-null here while the contract's is optional, because the decision belongs at
 * the boundary: the controller is the only layer allowed to read a clock (RULE B21), so it substitutes
 * "now" and everything below receives an instant. An optional instant travelling further in would be a
 * use case that could not be tested against a past moment.
 *
 * <p>{@code settleInCurrency} is null when the client did not ask for one, which is not the same as asking
 * for the debt currency: the first is silence and the second is a claim, and only the second should ever
 * be checked against a rate.
 *
 * @param amountMinor      the amount in the debt currency's minor units; must be strictly positive
 * @param currency         the debt currency's code, resolved to an exponent by the use case
 * @param settleInCurrency the currency the creditor is to be paid in, or null for the debt currency
 * @param memo             free text authored by a member; never parsed for meaning (ADR 0014)
 * @param idempotencyKey   the submitting operation's key, from the required header
 */
public record NewObligation(
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        long amountMinor,
        String currency,
        String settleInCurrency,
        String memo,
        Instant occurredAt,
        String idempotencyKey) {

    public NewObligation {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(debtorId, "debtorId");
        Objects.requireNonNull(creditorId, "creditorId");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(memo, "memo");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /** Whether the client asked for the creditor to be paid in a currency the debt is not in. */
    public boolean isCrossCurrency() {
        return settleInCurrency != null && !settleInCurrency.equals(currency);
    }
}
