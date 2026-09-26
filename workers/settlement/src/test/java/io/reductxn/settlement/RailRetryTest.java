package io.reductxn.settlement;

import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.PlannedTransfer;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.TransferStatus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when a rail does not answer, and when it says no.
 *
 * <p>Two mechanisms, tested together because they only make sense together: a per-transfer <em>backoff</em> (the
 * same instruction is not offered again immediately) and a per-rail <em>circuit breaker</em> (a rail that is down
 * for everybody is not called at all). The time is the test's - {@link MovingClockConfiguration} replaces the
 * clock bean - so an hour of waiting is one line, and the waits are asserted exactly rather than inferred.
 *
 * <p><strong>The rail here is the guarded stub, built the way {@code UseCaseConfiguration} builds the real
 * one</strong>: the same factory, the same policy type, the test's clock. The application's own rail is the mock,
 * which cannot fail, so no test can trip a breaker in front of it; what this class proves is the composition the
 * deployment uses, applied to a rail that does answer wrong.
 */
@SpringBootTest(
        classes = {
                SettlementWorkerApplication.class,
                MovingClockConfiguration.class,
                GuardedStubConfiguration.class},
        properties = {
                "reductxn.settlement.retry.base=PT5S",
                "reductxn.settlement.retry.cap=PT1M",
                // The breaker out of the way: this class is about the per-transfer wait, and a breaker opening
                // part-way through a test would refuse a call the test was about to assert on. It has its own
                // context either way, because its configuration differs from the shared one.
                "reductxn.rail.breaker.failures-before-opening=100000",
                "reductxn.rail.breaker.cooldown=PT1H"})
class RailRetryTest extends WorkerDatabaseTest {

    private static final Duration BASE = Duration.ofSeconds(5);

    @Autowired
    private StubRail rail;

    @Autowired
    private MovingClockConfiguration.MovingClock clock;

    @Autowired
    private DriveRuns driveRuns;

    @BeforeEach
    void resetTheRail() {
        rail.reset();
    }

    @Test
    void anUnansweredCallIsCountedAndItsRetryScheduled() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        Instant atFirstAttempt = clock.instant();
        ComputedRun executed = readBack(run.run().id());
        PlannedTransfer after = onlyTransferOf(executed);

        assertThat(after.status())
                .as("the rail never took it, so it is `authorized` rather than `failed`: nothing was refused and "
                        + "nothing moved")
                .isEqualTo(TransferStatus.AUTHORIZED);
        assertThat(after.unansweredAttempts()).isEqualTo(1);
        assertThat(after.nextAttemptAt())
                .as("and the retry is scheduled rather than repeated on the next pass")
                .isEqualTo(atFirstAttempt.plus(BASE));
        assertThat(after.railDetail()).contains("rails.unreachable");
        assertThat(executed.run().status()).isEqualTo(RunStatus.EXECUTING);
    }

    @Test
    void theSameTransferIsNotOfferedAgainUntilItsWaitIsOver() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        Instant scheduled = onlyTransferOf(readBack(run.run().id())).nextAttemptAt();

        // A scheduled pass, and then an operator asking for this one run by name: a backoff is the rail's health
        // rather than a stampede guard, so forcing the run does not force the call.
        driveRuns.driveDue(clock.instant().minusSeconds(1), 10);
        driveRuns.driveOne(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("offered once, and not again while it is waiting")
                .isEqualTo(1);
        assertThat(onlyTransferOf(readBack(run.run().id())).nextAttemptAt()).isEqualTo(scheduled);

        clock.advance(BASE.plusSeconds(1));
        readBack(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("and offered again once the wait is over")
                .isEqualTo(2);
        assertThat(onlyTransferOf(readBack(run.run().id())).unansweredAttempts())
                .as("the second failure is the second in a row, which is what makes the next wait longer")
                .isEqualTo(2);
    }

    @Test
    void theWaitDoublesWithEachConsecutiveFailureAndStopsAtTheCap() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        // The waits for base PT5S and cap PT1M: 5s, 10s, 20s, 40s, then the cap for good.
        long[] expectedSeconds = {5, 10, 20, 40, 60, 60};

        for (int attempt = 0; attempt < expectedSeconds.length; attempt++) {
            Instant before = clock.instant();
            ComputedRun executed = readBack(run.run().id());

            assertThat(onlyTransferOf(executed).unansweredAttempts()).isEqualTo(attempt + 1);
            assertThat(onlyTransferOf(executed).nextAttemptAt())
                    .as("wait number %d", attempt + 1)
                    .isEqualTo(before.plusSeconds(expectedSeconds[attempt]));

            clock.advance(Duration.ofSeconds(expectedSeconds[attempt]).plusSeconds(1));
        }
    }

    @Test
    void anAnswerForgetsTheFailures() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        readBack(run.run().id());
        clock.advance(BASE.plusSeconds(1));
        readBack(run.run().id());

        rail.scripting(transfer.railRef(), StubRail.Behaviour.CONFIRM);
        clock.advance(Duration.ofSeconds(11));

        ComputedRun executed = readBack(run.run().id());
        PlannedTransfer after = onlyTransferOf(executed);

        assertThat(after.status()).isEqualTo(TransferStatus.CONFIRMED);
        assertThat(after.unansweredAttempts())
                .as("reset rather than accumulated: a rail that is answering is not made to wait because of "
                        + "failures it has recovered from")
                .isZero();
        assertThat(after.nextAttemptAt()).isNull();
        assertThat(executed.run().status()).isEqualTo(RunStatus.DONE);
    }

    @Test
    void aRefusedInstructionFailsTheTransferAndTheRunAtOnce() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.REJECT);

        ComputedRun executed = readBack(run.run().id());
        PlannedTransfer after = onlyTransferOf(executed);

        assertThat(after.status())
                .as("the rail answered that it will not take this instruction, so there is nothing to retry: this "
                        + "is the classification doing its work, where an unclassified failure would leave the "
                        + "transfer offered for ever")
                .isEqualTo(TransferStatus.FAILED);
        assertThat(after.unansweredAttempts()).isZero();
        assertThat(after.nextAttemptAt()).isNull();
        assertThat(after.railDetail()).contains("rails.rejected");
        assertThat(executed.run().status()).as("RULE S3: the run failed, and nothing was rolled back")
                .isEqualTo(RunStatus.FAILED);

        clock.advance(Duration.ofMinutes(5));
        driveRuns.driveDue(clock.instant().minusSeconds(1), 10);

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("and no later pass offers it again")
                .isEqualTo(1);
    }

    private ComputedRun approvedRunReadyToMove() {
        obligation(ALICE, BORIS, 10_000L, "INR", 2);
        ComputedRun run = computed(new RunRequest(GROUP, AS_OF));
        approveEveryone(run);

        return run;
    }

    private PlannedTransfer onlyTransferOf(ComputedRun run) {
        assertThat(run.transfers()).hasSize(1);
        return run.transfers().get(0);
    }

    private ComputedRun readBack(UUID runId) {
        return ok(executeRun.execute(runId));
    }
}
