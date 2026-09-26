package io.reductxn.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's response for an approval: what the record now says about a member's answer.
 *
 * <p><strong>There is no {@code counted} field, and its absence is the contract's claim rather than a
 * simplification.</strong> This deployable records the answer; the run's quorum decides what it means, in the module
 * that owns the run. A boolean here would be this API answering a question it does not compute - and it would be
 * wrong the moment a member answered before the run was ready for answers, which is a case the run handles and this
 * response cannot see. The contract's own word for this response is "recorded", and it is doing real work.
 */
public record ApprovalResponse(UUID runId, UUID memberId, Instant recordedAt) {
}
