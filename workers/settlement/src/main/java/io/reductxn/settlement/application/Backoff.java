package io.reductxn.settlement.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * How long to wait before offering a rail the same transfer again.
 *
 * <p><strong>Exponential, capped, and without jitter - and each of those is a decision.</strong> Exponential,
 * because a rail that has been unreachable four times is unlikely to be back within the same second. Capped,
 * because a transfer must not drift into being retried once a day by accident: the cap is the difference between
 * "careful" and "forgotten". Without jitter, because the transfers in one run fail together and spreading them
 * would need a source of randomness whose only purpose is to look sophisticated - the cap bounds the retry burst
 * to one per transfer per cap, and the circuit breaker in `libs/rails` is what stops the burst against a rail
 * that is down for a long time.
 *
 * <p><strong>The delay is computed from consecutive unanswered attempts, not from total attempts.</strong> A
 * transfer that has been polled forty times and then meets a network blip waits {@code base}, not an hour: the
 * rail has been answering, so there is nothing to be careful about. {@code ExecuteRunService} resets the count
 * whenever an answer arrives, which is what makes that true.
 *
 * <p>A pure function of a count and an instant, with no clock of its own - the caller passes "now", so the policy
 * is testable without waiting and the same count always produces the same wait.
 *
 * @param base the first wait, and the wait after any answer
 * @param cap  the longest wait, which is also what keeps a transfer's retries from becoming a memory
 */
public record Backoff(Duration base, Duration cap) {

    public Backoff {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(cap, "cap");

        if (base.isNegative()) {
            throw new IllegalArgumentException(
                    "a base wait of " + base + " would retry before the failure it is reacting to. Zero is "
                            + "allowed and means 'retry at once', which is what a deployment with a rail that "
                            + "cannot fail - or a test that is not about backoff - should say");
        }

        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException(
                    "a cap of " + cap + " is shorter than the base wait of " + base + ", so the policy would "
                            + "retry sooner the more it failed");
        }
    }

    /**
     * When a transfer that has just gone unanswered this many times in a row may be offered again.
     *
     * <p>Doubling from {@code base} until the cap, which for the defaults is 30s, 1m, 2m, 4m, 8m, 15m and then 15m
     * for ever - so a rail that stays down is asked at most four times an hour per transfer.
     */
    public Instant nextAttempt(int consecutiveUnanswered, Instant now) {
        Objects.requireNonNull(now, "now");

        if (consecutiveUnanswered < 1) {
            throw new IllegalArgumentException(
                    "a transfer is only held back after an attempt that was not answered, but this says "
                            + consecutiveUnanswered + " - nothing has been attempted, so there is nothing to back "
                            + "off from");
        }

        Duration wait = base;

        for (int attempt = 1; attempt < consecutiveUnanswered && wait.compareTo(cap) < 0; attempt++) {
            wait = wait.multipliedBy(2);
        }

        return now.plus(wait.compareTo(cap) > 0 ? cap : wait);
    }
}
