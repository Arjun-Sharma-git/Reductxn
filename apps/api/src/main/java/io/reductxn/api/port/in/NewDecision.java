package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A member's answer on a run, as {@code POST /v1/runs/{runId}/decisions} carries it.
 *
 * <p><strong>The member is gone from this record, and its absence is what ADR 0053 records.</strong> It used to hold
 * the member the client named, with a paragraph explaining that this build's boundary let a client name them. The
 * answering member is now the caller, so the caller travels beside this record
 * ({@link RecordDecision#record}) rather than inside it: a caller is *who is asking* rather than *what is being
 * asked*, which is the split {@code NewCycle} and {@code OpenCycle} already keep. The field was removed rather than
 * kept and filled in at the boundary on purpose - a {@code UUID memberId} that the controller happens to set is a
 * field the next caller can set to somebody else, and the type would go on permitting an answer by a member who
 * never gave one.
 *
 * <p><strong>An answer is not a decision about the run.</strong> Approving does not approve anything: the run's own
 * quorum arithmetic over its participants is what does, and it happens in {@code workers/settlement}. This type
 * carries a member's act, and the difference is the whole subject of ADR 0020.
 *
 * <p>{@code recordedAt} is non-null while the wire may not carry it, because the boundary is the only layer allowed
 * to read a clock (RULE B21): the controller substitutes "now" and everything below receives an instant, which is
 * what makes this use case testable against a fixed moment.
 *
 * @param runId      the run being answered
 * @param decision   the answer, one of the run's two words
 * @param recordedAt the instant the answer was recorded, read from the clock at the boundary
 */
public record NewDecision(UUID runId, String decision, Instant recordedAt) {

    public NewDecision {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
