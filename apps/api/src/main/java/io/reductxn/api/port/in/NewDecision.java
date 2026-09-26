package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's answer on a run, as {@code POST /v1/runs/{runId}/approve} carries it.
 *
 * <p><strong>The member is named by the client, and that is the identity gap rather than a decision.</strong> There
 * is no authentication in this build - an obligation's {@code debtorId} is equally client-supplied - and groups and
 * membership do not exist as tables yet (MASTER-PLAN's phase 3). What matters here is that this type does not
 * pretend otherwise: it records *who the client says answered*, and the worker decides whether that member's answer
 * counts. Fixing it is the identity slice, and it changes the boundary rather than this record.
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
