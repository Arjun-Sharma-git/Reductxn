package io.reductxn.api.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * The contract's {@code TransferInstruction} schema, for {@code GET /v1/runs/{runId}/transfers}.
 *
 * <p><strong>{@code tax} is not here, and the contract's {@code required} list changes with this slice.</strong> The
 * contract required a per-transfer tax amount; the worker stores tax per *currency* rather than per movement - the
 * plan's tax figure and what netting saved are partition columns, because the saving is a property of netting rather
 * than of any one payment - so there is nothing to put in the field. Fabricating it (splitting the partition's tax
 * across its transfers) would be this module inventing an arithmetic the worker never ran, which is the line this
 * whole read path is drawn on. The tax a group actually cares about is on the run summary, per currency.
 *
 * <p>{@code fxLinkId} is absent for a related reason: the L9 pairing between currencies is a *ledger* fact, recorded
 * on the entries a confirmed transfer books, not a column of the transfer row. {@code failureCode} is absent because
 * the row carries the rail's own words ({@code railDetail}, surfaced as {@code failureReason}) and no stable code -
 * a machine-readable code would be worth having and nothing writes one yet.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferInstructionResponse(
        UUID fromUserId,
        UUID toUserId,
        String toAccountType,
        MoneyDto amount,
        String status,
        String railRef,
        String failureReason) {
}
