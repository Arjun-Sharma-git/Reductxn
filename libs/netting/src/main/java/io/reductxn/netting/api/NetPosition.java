package io.reductxn.netting.api;

import io.reductxn.ledger.api.Money;

import java.util.Objects;
import java.util.UUID;

/**
 * One member's position: the single number that settles everything they owe and are owed.
 *
 * <p>The sign of {@link #net()} carries the direction. This is the number shown to a member as
 * "you pay X" or "you receive Y".
 *
 * @param userId the member
 * @param net    negative means they pay, positive means they receive, zero means they are flat
 */
public record NetPosition(UUID userId, Money net) {

    public NetPosition {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(net, "net");
    }

    public NetDirection direction() {
        if (net.isNegative()) {
            return NetDirection.PAY;
        }
        if (net.isPositive()) {
            return NetDirection.RECEIVE;
        }
        return NetDirection.FLAT;
    }

    /** Magnitude of the position, for display without its direction. */
    public Money magnitude() {
        return net.absolute();
    }
}
