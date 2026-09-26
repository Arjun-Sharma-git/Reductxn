package io.reductxn.booking.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

        // Truncated to microseconds, because that is what the ledger can store and the hash has to be a
        // function of what is stored.
        //
        // This is not tidiness. `occurredAt` becomes the entry's `occurred_at` (a Postgres timestamptz,
        // microsecond precision) and it is also part of the canonical encoding the entry's hash covers (L4).
        // An instant with nanoseconds therefore produces an entry whose stored row rehashes to a *different*
        // value - so the chain an offline verifier recomputes from the rows would not match the hashes in
        // them, and the ledger's central claim, that it can be verified from its own rows, would be false.
        // Keeping the nanoseconds in Java and losing them in the database is the shape of bug that only
        // appears when someone verifies history.
        //
        // Truncating rather than refusing, because the two are the same commitment: nothing ends a commitment
        // in the nanosecond after the microsecond it was recorded in. Refusing would also make a client's
        // timestamp with more digits than the ledger needs into a failed request.
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }
}
