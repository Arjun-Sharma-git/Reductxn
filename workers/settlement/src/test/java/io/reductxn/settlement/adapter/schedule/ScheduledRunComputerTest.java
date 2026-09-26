package io.reductxn.settlement.adapter.schedule;

import io.reductxn.settlement.port.in.ComputeOpenedCycles;
import io.reductxn.settlement.port.in.ComputedCycles;
import io.reductxn.settlement.port.in.RunStatus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The computer's cadence, without a database and without a scheduler.
 *
 * <p>The same seam, and the same reason, as {@code ScheduledRunDriverTest}: what is decided here is how many cycles
 * one pass may take and what happens when a pass throws. A Spring context would prove the annotation is registered
 * - which Spring's own tests cover, and which the container tests would race with - and would hide the arithmetic.
 *
 * <p>What it does <em>not</em> test is what a pass computes, because a tick decides nothing about that: this class
 * hands the batch to the use case and reports what came back. The decisions about a plan are
 * {@code ComputeRunService}'s, and the SQL behind them is {@code ComputeOpenedCyclesTest}'s.
 */
class ScheduledRunComputerTest {

    private static final int BATCH = 25;

    @Test
    void aTickAsksForTheBatchItWasConfiguredWith() {
        RecordingComputer port = new RecordingComputer();

        computer(port).compute();

        assertThat(port.batchSizes).containsExactly(BATCH);
    }

    @Test
    void aTickWithNothingToDoReturnsQuietly() {
        RecordingComputer port = new RecordingComputer();

        assertThatCode(() -> computer(port).compute()).doesNotThrowAnyException();
        assertThat(port.batchSizes).hasSize(1);
    }

    @Test
    void aTickSurvivesAFaultSoTheNextOneCanHappen() {
        RecordingComputer port = new RecordingComputer();
        port.fault = new IllegalStateException("the database went away");

        assertThatCode(() -> computer(port).compute())
                .as("a scheduled task that throws is a schedule Spring logs and continues, but a cadence that let "
                        + "the fault out would make every pass look like a failure of the worker rather than of one "
                        + "pass - and the cycles are still there in a minute")
                .doesNotThrowAnyException();

        assertThat(port.batchSizes).hasSize(1);
    }

    @Test
    void aTickWithOutcomesStillReturns() {
        RecordingComputer port = new RecordingComputer();
        port.report = new ComputedCycles(List.of(
                new ComputedCycles.CycleOutcome(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                        UUID.randomUUID(), RunStatus.AWAITING_APPROVAL, null, null),
                new ComputedCycles.CycleOutcome(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                        null, null, "settlement.nothing_to_settle: nothing open at that instant", null),
                new ComputedCycles.CycleOutcome(UUID.randomUUID(), UUID.randomUUID(), Instant.EPOCH,
                        null, null, null, "java.lang.IllegalStateException: broken")));

        assertThatCode(() -> computer(port).compute()).doesNotThrowAnyException();

        assertThat(port.report.faulted())
                .as("the report the tick logged from is the use case's, counted once")
                .isEqualTo(1);
    }

    private static ScheduledRunComputer computer(ComputeOpenedCycles port) {
        return new ScheduledRunComputer(port, BATCH);
    }

    /** The port, recording what a tick asked for. */
    private static final class RecordingComputer implements ComputeOpenedCycles {

        private final List<Integer> batchSizes = new ArrayList<>();
        private ComputedCycles report = new ComputedCycles(List.of());
        private RuntimeException fault;

        @Override
        public ComputedCycles computeOpened(int batchSize) {
            batchSizes.add(batchSize);

            if (fault != null) {
                throw fault;
            }

            return report;
        }
    }
}
