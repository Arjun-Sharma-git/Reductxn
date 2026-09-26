package io.reductxn.booking.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A commitment's provenance: which one it is, whose book, when, and on whose behalf.
 *
 * <p>Who promised whom and how much are not here. Those are the {@link io.reductxn.planning.api.CycleObligation}
 * being booked, which already models them - and a copy of them here would be a second place for the two to
 * disagree about the same commitment.
 *
 * <p>{@code occurredAt} is supplied rather than read from a clock. A booking that read one would produce a
 * different entry on a retry, and this value is the record of when the commitment was made - which the
 * caller owns (P4).
 *
 * @param obligationId   the commitment's identity, from which every entry id is derived
 * @param groupId        the group whose book this belongs to
 * @param occurredAt     when the commitment was made
 * @param idempotencyKey the submitting operation's key, or null when there is none
 * @param createdBy      the member who recorded it, or null for a system-generated commitment
 */
public record Commitment(
        UUID obligationId,
        UUID groupId,
        Instant occurredAt,
        String idempotencyKey,
        UUID createdBy) {

    public Commitment {
        Objects.requireNonNull(obligationId, "obligationId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
