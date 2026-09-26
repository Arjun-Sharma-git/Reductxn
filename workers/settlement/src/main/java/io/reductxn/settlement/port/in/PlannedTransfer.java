package io.reductxn.settlement.port.in;

import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;

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
 */
public record PlannedTransfer(
        UUID id,
        Currency currency,
        UUID fromUserId,
        UUID toUserId,
        Money amount,
        String railRef,
        TransferStatus status,
        String railDetail) {

    public PlannedTransfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(fromUserId, "fromUserId");
        Objects.requireNonNull(toUserId, "toUserId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(railRef, "railRef");
        Objects.requireNonNull(status, "status");

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
}
