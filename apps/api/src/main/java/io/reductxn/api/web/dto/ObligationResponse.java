package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code Obligation} schema.
 *
 * <p><strong>Nulls mean absent</strong>, as in the preview's response. A voided obligation is the only case
 * where a field here is absent today, and it is absent because nothing can void one yet rather than because
 * the schema says so.
 *
 * <p>{@code settleInCurrency} is always written, including for an ordinary obligation where it equals
 * {@code amount.currency}. The contract calls it optional and says it "defaults to {@code amount.currency}",
 * so omitting it would be correct - and stating it is better: what the creditor is to be paid in is a fact
 * about the commitment, and a client that had to fall back to the debt currency would be computing something
 * the server already knows.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObligationResponse(
        UUID id,
        UUID groupId,
        UUID debtorId,
        UUID creditorId,
        MoneyDto amount,
        String settleInCurrency,
        String memo,
        String status,
        Instant occurredAt) {
}
