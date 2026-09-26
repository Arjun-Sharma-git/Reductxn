package io.reductxn.api.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's response body for {@code POST /runs/{runId}/decisions}: what the record now says about a member's
 * answer.
 *
 * <p>What the row now holds: the run, the member, the answer as it stands, and when the server recorded it. The
 * answer is echoed for the reason the read endpoint reports one - a member who answered can see which answer is on
 * the record - and because a client that sent a rejection has no other way to tell an approval it recorded from the
 * other one.
 *
 * <p><strong>There is still no {@code counted} field, and its absence is the contract's claim rather than a
 * simplification.</strong> This deployable records the answer; the run's quorum decides what it means, in the module
 * that owns the run. A boolean here would be this API answering a question it does not compute - and it would be
 * wrong the moment a member answered before the run was ready for answers, which is a case the run handles and this
 * response cannot see. The contract's own word for this response is "recorded", and it is doing real work. The same
 * argument is why a rejection is not marked as final here: that it ended the run is the run's judgement, and the
 * member reads it in the run's status.
 */
public record DecisionResponse(UUID runId, UUID memberId, String decision, Instant recordedAt) {
}
