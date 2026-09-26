package io.reductxn.booking.api;

import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One movement a settlement run has made, as the ledger has to record it.
 *
 * <p>An obligation is what a group promised; this is money actually moving. The two are separate bookings
 * because they are separate events: a commitment is recorded when it is agreed, and it is discharged when a
 * cycle settles - which may be weeks later, by a different process, and is the only moment at which the rail
 * and the ledger agree that a member's account changed.
 *
 * <p><strong>{@code transferId} is the identity, and it is what makes the booking idempotent.</strong> The
 * run's transfer is keyed by a reference derived from its own coordinates (RULE S2), so a replayed run, a
 * retried submission and two worker instances executing the same transfer all name the same thing — and the
 * entry's id is derived from it. Nothing here depends on the caller generating a fresh id.
 *
 * <p><strong>{@code railReference} is carried because it is the audit trail, not because the ledger needs
 * it.</strong> It is stored as the entry's {@code idempotencyKey}, which is where an auditor looks to connect
 * a posting to the instruction that caused it, and it is already unique per transfer by construction.
 *
 * <p>{@code occurredAt} is supplied rather than read from a clock, as everywhere in this module, and it is
 * truncated to microseconds in the constructor — because that is what the ledger can store and the hash has
 * to be a function of what is stored ({@link Commitment} carries the long version of that argument).
 *
 * @param transferId    the run's transfer, from which every id here is derived
 * @param groupId       the group whose book this belongs to
 * @param fromUserId    the payer, whose claim or liability this reduces
 * @param toUserId      the payee, which is {@code fx_clearing} for a leg of the group's external requirement
 * @param amount        exact amount in minor units, strictly positive (L2)
 * @param occurredAt    when the movement was confirmed, to microsecond precision
 * @param railReference the rail's idempotency key for this movement, or null when there is none
 */
public record SettledTransfer(
        UUID transferId,
        UUID groupId,
        UUID fromUserId,
        UUID toUserId,
        Money amount,
        Instant occurredAt,
        String railReference) {

    public SettledTransfer {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(fromUserId, "fromUserId");
        Objects.requireNonNull(toUserId, "toUserId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(occurredAt, "occurredAt");

        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException(
                    "a settled transfer moves money between two participants, but both were " + fromUserId);
        }
        if (amount.isZero()) {
            throw new IllegalArgumentException(
                    "a settled transfer of zero " + amount.currency().code() + " cannot be posted (L2): the "
                            + "money did not move, so there is no entry to make");
        }
        if (amount.isNegative()) {
            throw new IllegalArgumentException(
                    "a settled transfer books a magnitude, but the amount was " + amount
                            + ". Direction is the payer and the payee, not the sign");
        }
        if (railReference != null && railReference.isBlank()) {
            throw new IllegalArgumentException(
                    "a rail reference is either absent or meaningful; a blank one would look set while "
                            + "matching every other blank reference");
        }

        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }
}
