package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's answer on a run, as {@code POST /v1/runs/{runId}/decisions} carries it. (The path was
 * {@code /runs/{runId}/approve} until refusals were expressible; this javadoc went on saying {@code approve} for a
 * while after the contract stopped.)
 *
 * <p><strong>The member is named by the client, and that is a property of this operation rather than of the
 * build.</strong> Authentication exists (ADR 0042) and the group writes resolve a caller (ADR 0043, 0047-0049), so
 * this is the last operation whose *acting* member arrives in the body - an obligation's {@code debtorId} is
 * client-supplied too, and it names the debt's party rather than its author. What matters here is that this type
 * does not pretend otherwise: it records *who the client says answered*, and the run decides whether that member's
 * answer counts (ADR 0016). Closing that boundary is the same change the group writes made, and it changes the
 * boundary rather than this record.
 *
 * <p>{@code recordedAt} is non-null while the wire may not carry it, because the boundary is the only layer allowed
 * to read a clock (RULE B21): the controller substitutes "now" and everything below receives an instant, which is
 * what makes this use case testable against a fixed moment.
 *
 * <p><strong>An answer is not a decision about the run.</strong> Approving does not approve anything: the run's own
 * quorum arithmetic over its participants is what does, and it happens in {@code workers/settlement}. This type
 * carries a member's act, and the difference is the whole subject of ADR 0020.
 *
 * @param runId      the run being answered
 * @param memberId   the member the client says is answering
 * @param recordedAt the instant the answer was recorded, read from the clock at the boundary
 */
public record NewDecision(UUID runId, UUID memberId, String decision, Instant recordedAt) {

    public NewDecision {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
