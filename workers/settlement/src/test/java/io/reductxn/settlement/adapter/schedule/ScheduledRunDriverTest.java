package io.reductxn.settlement.adapter.schedule;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The cadence, without a database and without a scheduler.
 *
 * <p>The test is at this seam rather than through {@code @SpringBootTest} because that is where the decisions are:
 * how stale an attempt must be, how many runs one pass may take, and what happens when a pass throws. A Spring
 * context would prove the annotation is registered - which Spring's own tests cover, and which the container
 * tests would otherwise race with - and would hide the arithmetic.
 */
class ScheduledRunDriverTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(2);
    private static final int BATCH = 25;

    @Test
    void aTickAsksForTheRunsThatHaveNotBeenAttemptedWithinTheWindow() {
        RecordingDriver port = new RecordingDriver();

        driver(port).drive();

        assertThat(port.cutOffs)
                .as("the window is the anti-stampede guard: a pass asks for runs whose last attempt is older "
                        + "than now minus the window, and never for the runs themselves")
                .containsExactly(NOW.minus(WINDOW));
        assertThat(port.batchSizes).containsExactly(BATCH);
    }

    @Test
    void aTickThatFindsNothingIsSilentRatherThanFailing() {
        RecordingDriver port = new RecordingDriver();

        assertThatCode(() -> driver(port).drive()).doesNotThrowAnyException();
        assertThat(port.cutOffs).hasSize(1);
    }

    @Test
    void aTickSurvivesAFaultSoTheNextOneCanHappen() {
        RecordingDriver port = new RecordingDriver();
        port.fault = new IllegalStateException("the database went away");

        assertThatCode(() -> driver(port).drive())
                .as("a scheduled task that throws is a schedule Spring logs and continues, but a driver that let "
                        + "the fault out would make every pass look like a failure of the worker rather than of "
                        + "one attempt - and the run is still there in a minute")
                .doesNotThrowAnyException();

        assertThat(port.cutOffs).hasSize(1);
    }

    @Test
    void aTickWithOutcomesStillReturns() {
        RecordingDriver port = new RecordingDriver();
        port.answer = new DrivenRuns(NOW, List.of(
                new DrivenRuns.RunOutcome(new UUID(0L, 1L), new UUID(0L, 2L), RunStatus.DONE, null),
                new DrivenRuns.RunOutcome(new UUID(0L, 3L), new UUID(0L, 2L), RunStatus.AWAITING_APPROVAL,
                        "settlement.quorum_not_met: 0 of 2")));

        assertThatCode(() -> driver(port).drive()).doesNotThrowAnyException();
        assertThat(port.answer.driven()).isEqualTo(1);
        assertThat(port.answer.refused()).isEqualTo(1);
    }

    private ScheduledRunDriver driver(DriveRuns port) {
        return new ScheduledRunDriver(port, Clock.fixed(NOW, ZoneOffset.UTC), WINDOW, BATCH);
    }

    /** A port that records what it was asked, and can be told to fail. */
    private static final class RecordingDriver implements DriveRuns {

        private final List<Instant> cutOffs = new ArrayList<>();
        private final List<Integer> batchSizes = new ArrayList<>();
        private DrivenRuns answer = new DrivenRuns(NOW, List.of());
        private RuntimeException fault;

        @Override
        public Result<DrivenRuns> driveDue(Instant attemptedBefore, int batchSize) {
            cutOffs.add(attemptedBefore);
            batchSizes.add(batchSize);

            if (fault != null) {
                throw fault;
            }

            return Result.ok(answer);
        }

        @Override
        public Result<DrivenRuns> driveOne(UUID runId) {
            throw new AssertionError(
                    "a cadence drives what is due, never a named run: naming one is an operator's act, and this "
                            + "pass has nobody's decision to act on");
        }
    }
}
