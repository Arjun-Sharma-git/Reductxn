package io.reductxn.rails.api;

import java.time.Duration;
import java.util.Objects;

/**
 * When to stop calling a rail, and for how long.
 *
 * <p><strong>What a circuit breaker is for here.</strong> Not throughput and not latency: the point is that a
 * rail which is failing costs something to fail against - rate limits, an incident's worth of noise, a retry
 * storm that arrives at a service already in trouble. reductxn has no way to help a rail recover, so the useful
 * thing it can do is stop asking for a while.
 *
 * <p><strong>The count is consecutive retryable refusals</strong> ({@link PaymentRail.RailUnreachable}). A rail
 * that answers "no" ({@link PaymentRail.RailRejected}) is a working rail with an opinion, so it must not trip the
 * breaker - otherwise a group with insufficient funds would stop everyone else's settlement. This is the second
 * place the port's classification is load-bearing, and the reason it is in the type rather than in a message.
 *
 * @param failuresBeforeOpening how many calls in a row must go unanswered before the rail is left alone
 * @param cooldown              how long it is left alone before one call is allowed through to test it
 */
public record BreakerPolicy(int failuresBeforeOpening, Duration cooldown) {

    public BreakerPolicy {
        Objects.requireNonNull(cooldown, "cooldown");

        if (failuresBeforeOpening < 1) {
            throw new IllegalArgumentException(
                    "a breaker opens after at least one failure, but this policy says " + failuresBeforeOpening
                            + ". A deployment that wants no breaker should not configure one with a zero here - "
                            + "a breaker that opens on success is a rail nothing can ever settle through");
        }

        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "a cooldown of " + cooldown + " would leave the rail alone for no time at all, which is a "
                            + "breaker that does nothing while looking like protection");
        }
    }
}
