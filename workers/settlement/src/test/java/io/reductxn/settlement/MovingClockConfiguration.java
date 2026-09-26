package io.reductxn.settlement;

import io.reductxn.rails.api.PaymentRail;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.TransferStatus;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * A clock the tests can move, replacing the application's fixed one.
 *
 * <p><strong>Why this is possible at all, and why it is worth a class.</strong> Nothing below {@code application}
 * in this module reads a clock: the use cases take their instants from the bean in {@code UseCaseConfiguration},
 * and every timestamp comes from it. That is a rule about testability that was written down before it had a
 * beneficiary, and this is the beneficiary - a backoff, a cooldown and a retry window are all decisions about
 * time, and a test that had to sleep through them would either be slow, flaky, or (most likely) not written.
 *
 * <p>It is {@code @Primary} rather than a replacement, because a test context that quietly lacked the
 * application's clock bean would be a different application.
 */
@TestConfiguration
public class MovingClockConfiguration {

    @Bean
    @Primary
    MovingClock movingClock() {
        return new MovingClock(Instant.now());
    }

    /** A clock whose instant a test sets. Everything else about it is UTC, like the application's. */
    public static final class MovingClock extends Clock {

        private Instant now;

        private MovingClock(Instant start) {
            // Truncated, and not because anything here needs the precision: `timestamptz` keeps microseconds, so
            // a clock reading nanoseconds would make every assertion about a stored instant a question about
            // rounding - "expected 09:04:08.199753121Z but was 09:04:03.199753Z" is a test that reports the
            // database's resolution rather than the behaviour it meant to check.
            this.now = start.truncatedTo(ChronoUnit.MICROS);
        }

        /** Moves time forward, which is the only direction a clock may go. */
        public void advance(Duration amount) {
            if (amount.isNegative()) {
                throw new IllegalArgumentException(
                        "a clock that can go backwards would let a test produce a state no deployment can "
                                + "reach - a transfer whose next attempt is before its last one");
            }

            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
