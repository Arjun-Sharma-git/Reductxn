package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An answer as it now stands on the record.
 *
 * <p><strong>It says "recorded", not "counted", and the wording is the contract's.</strong> The OpenAPI operation
 * this serves documents its {@code 200} as "Approval recorded", and that is exactly what has happened: the member's
 * answer exists where the run can read it. Whether it reaches quorum - or whether it can count at all, for a member
 * who is not a participant - is decided by the run's own rows, in the module that owns the run, and answering a
 * {@code 200} with "approved" would be this deployable claiming a judgement it does not make (ADR 0020).
 *
 * <p>Echoing the member and the instant is not decoration. A client that recorded an answer cannot read the run
 * through this API yet (the contract's {@code GET /runs/{runId}} is the next slice), so this response is the only
 * confirmation it gets that the answer was written under the identity and at the moment it asked for.
 *
 * @param recordedAt the instant stored on the row, echoed rather than re-read: it is what this answer was recorded
 *                   as, and a later read could show a different clock than the one that wrote it
 */
public record RecordedApproval(UUID runId, UUID memberId, Instant recordedAt) {

    public RecordedApproval {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
