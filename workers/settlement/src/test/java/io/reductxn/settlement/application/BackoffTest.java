package io.reductxn.settlement.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wait between attempts, as arithmetic rather than as behaviour.
 *
 * <p>Split out from the container tests because this is where the policy is <em>decided</em>: what a count produces
 * is a pure function of a count and an instant, so it can be asserted exactly - 30s, 1m, 2m, then the cap - instead
 * of being inferred from timestamps that a test had to arrange. The container tests then only have to prove that
 * the value this returns is what reaches the row.
 */
class BackoffTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(30);
    private static final Duration CAP = Duration.ofMinutes(15);

    private final Backoff backoff = new Backoff(BASE, CAP);

    @Test
    void itDoublesFromTheBaseAndStopsAtTheCap() {
        assertThat(backoff.nextAttempt(1, NOW)).isEqualTo(NOW.plusSeconds(30));
        assertThat(backoff.nextAttempt(2, NOW)).isEqualTo(NOW.plusSeconds(60));
        assertThat(backoff.nextAttempt(3, NOW)).isEqualTo(NOW.plusSeconds(120));
        assertThat(backoff.nextAttempt(4, NOW)).isEqualTo(NOW.plusSeconds(240));
        assertThat(backoff.nextAttempt(5, NOW)).isEqualTo(NOW.plusSeconds(480));

        assertThat(backoff.nextAttempt(6, NOW))
                .as("900 seconds would be 15 minutes, and the cap is where it stops doubling - a transfer must "
                        + "not drift into being retried once a day by accident, which is the difference between "
                        + "careful and forgotten")
                .isEqualTo(NOW.plus(CAP));
        assertThat(backoff.nextAttempt(50, NOW))
                .as("and it stays there: no amount of failing makes the wait longer than the cap")
                .isEqualTo(NOW.plus(CAP));
    }

    @Test
    void aBaseOfZeroMeansTryAgainAtOnce() {
        Backoff immediate = new Backoff(Duration.ZERO, CAP);

        assertThat(immediate.nextAttempt(1, NOW)).isEqualTo(NOW);
        assertThat(immediate.nextAttempt(9, NOW)).isEqualTo(NOW);
    }

    @Test
    void aPolicyThatCannotBeComputedIsRefused() {
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(-1), CAP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before the failure it is reacting to");
        assertThatThrownBy(() -> new Backoff(CAP, BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than the base wait");
        assertThatThrownBy(() -> backoff.nextAttempt(0, NOW))
                .as("a wait is computed from an attempt that went unanswered, so nothing attempted means nothing "
                        + "to back off from")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nothing has been attempted");
    }
}
