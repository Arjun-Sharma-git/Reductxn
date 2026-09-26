package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One transfer a run records, and what has become of it.
 *
 * <p>A row read back as a value, not a row type: no jOOQ type crosses out of {@code adapter/persistence}
 * (RULE B16), so what the use case sees is a money movement with a payer, a payee and a state.
 *
 * <p>{@link #railRef} is RULE S2's idempotency key, derived from the transfer's own coordinates rather than
 * generated when the rail is called. It is stored with the transfer because it is what makes re-submitting a
 * no-op: the reference has to be the same string on the second attempt, and a value computed at submission
 * time could not promise that.
 *
 * @param id         the transfer's identity, derived from its rail reference so a duplicate insert collides
 * @param currency   the currency it moves in; a transfer moves one currency, always
 * @param fromUserId the payer
 * @param toUserId   the payee, which is {@code fx_clearing} for the legs of the group's external requirement
 * @param amount     exact amount in minor units, strictly positive
 * @param railRef    the reference the rail is idempotent on
 * @param status     where it stands
 * @param railDetail what the rail said, when it said anything: a failure's reason, or the confirmation's
 *                   origin. Null until a rail has been asked, which is why it is not simply a message
 * @param unansweredAttempts how many times in a row this transfer has been offered to the rail without an
 *                   answer. Any answer - including one that refuses the transfer - resets it, so it grows only
 *                   while a rail is unreachable, which is what makes it the input to the backoff
 * @param nextAttemptAt when this transfer may be offered to the rail again, or null when it may be offered now.
 *                   Only an unanswered call sets it; see {@code V506} for why the wait is stored as an instant
 */
public record PlannedTransfer(
        UUID id,
        Currency currency,
        UUID fromUserId,
        UUID toUserId,
        Money amount,
        String railRef,
        TransferStatus status,
        String railDetail,
        int unansweredAttempts,
        Instant nextAttemptAt) {

    public PlannedTransfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(fromUserId, "fromUserId");
        Objects.requireNonNull(toUserId, "toUserId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(status, "status");

        if (unansweredAttempts < 0) {
            throw new IllegalArgumentException(
                    "transfer " + id + " has been unanswered " + unansweredAttempts + " times, which is not a "
                            + "count: the schema's constraint says the same thing");
        }

        if (status.isResolved() && nextAttemptAt != null) {
            throw new IllegalArgumentException(
                    "transfer " + id + " is " + status.wireName() + " and waiting until " + nextAttemptAt
                            + ". A resolved transfer is never attempted again, so a wait on one is a claim that "
                            + "something will happen to it");
        }

        if (fromUserId.equals(toUserId)) {
            throw new IllegalArgumentException("transfer " + id + " has the same payer and payee");
        }
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "transfer " + id + " is denominated in " + amount.currency().code() + " but recorded in "
                            + currency.code() + ". A transfer moves one currency");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("transfer " + id + " moves " + amount + ", which is not a "
                    + "movement: the schema's CHECK constraint says a transfer moves something");
        }
        if (railRef.isBlank()) {
            throw new IllegalArgumentException("transfer " + id + " has a blank rail reference, so it cannot be "
                    + "submitted idempotently (RULE S2)");
        }
    }

    /**
     * Whether the rail may be asked about this transfer at this instant.
     *
     * <p>False while a backoff is running, and false once the transfer is resolved. The caller asks this rather
     * than comparing the instant itself, so that "how long a transfer waits" is answered in one place - the
     * transfer - rather than at every call site that walks a run's transfers.
     *
     * <p><strong>It applies to a manual pass as well as a scheduled one.</strong> A backoff is not a stampede
     * guard on the run - it is the rail's health, and an operator driving one run by hand should not be able to
     * hammer a rail that is down, however deliberately they asked.
     */
    public boolean isAttemptableAt(Instant now) {
        if (status.isResolved()) {
            return false;
        }

        return nextAttemptAt == null || !nextAttemptAt.isAfter(now);
    }
}
