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
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rail that has stopped answering, and what the worker does about it.
 *
 * <p>This class is about the <em>breaker</em> - the per-transfer backoff is {@code RailRetryTest}'s subject, and
 * the two are separated because the breaker's state cannot be reset between tests. Its counters live in memory
 * for the life of a Spring context, and a context is shared between the tests of a class, so a test that opened
 * the breaker would decide the behaviour of the next one. {@code @DirtiesContext} per test method is the honest
 * answer: the state under test is genuinely process-wide, so the test needs a fresh process's worth of state
 * rather than a reset method that only a test would ever call.
 *
 * <p>The policy here is deliberately trigger-happy (two failures, a one-minute cooldown) so a test can reach it
 * in seconds of <em>its own</em> clock - {@link MovingClockConfiguration} replaces the clock bean, so the
 * cooldown costs nothing to wait for.
 */
@SpringBootTest(
        classes = {
                SettlementWorkerApplication.class,
                MovingClockConfiguration.class,
                GuardedStubConfiguration.class},
        properties = {
                "reductxn.settlement.retry.base=PT5S",
                "reductxn.settlement.retry.cap=PT1M",
                "reductxn.rail.breaker.failures-before-opening=2",
                "reductxn.rail.breaker.cooldown=PT1M"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BrokenRailTest extends WorkerDatabaseTest {

    private static final Duration BASE = Duration.ofSeconds(5);
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

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
    void aRailThatIsDownIsLeftAloneRatherThanCalledByEveryPass() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        // Two unanswered calls is what this context's policy opens the breaker on - and the second needs its
        // backoff out of the way first.
        readBack(run.run().id());
        clock.advance(BASE.plusSeconds(1));
        readBack(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef())).as("two calls, two failures").isEqualTo(2);

        // Now the transfer is due again while the rail is still being left alone, which is the case the breaker
        // exists for: the wait is over, the rail is not.
        clock.advance(Duration.ofSeconds(11));
        ComputedRun executed = readBack(run.run().id());
        PlannedTransfer after = onlyTransferOf(executed);

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("the rail was not called at all: a breaker is the only thing reductxn can do about a rail "
                        + "that is costing something to fail against")
                .isEqualTo(2);
        assertThat(after.railDetail())
                .as("and the reason is recorded, in the port's own vocabulary, with the cooldown in it - so an "
                        + "operator reading the row can tell a quiet rail from a broken one")
                .contains("left alone until");
        assertThat(after.unansweredAttempts())
                .as("the refusal counts as an attempt that was not answered, so the two mechanisms compose: while "
                        + "the rail is left alone the transfer backs off too")
                .isEqualTo(3);

        // The cooldown expires and one call is allowed through to test the rail. It still fails, so the breaker
        // opens again - and the next attempt within the cooldown does not reach the rail either.
        clock.advance(COOLDOWN.plusSeconds(1));
        readBack(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef())).as("exactly one probe").isEqualTo(3);

        clock.advance(Duration.ofSeconds(2));
        readBack(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("and the probe's failure restarted the cooldown rather than letting a burst through")
                .isEqualTo(3);
    }

    @Test
    void aProbeThatSucceedsClosesTheBreakerAndTheRunFinishes() {
        ComputedRun run = approvedRunReadyToMove();
        PlannedTransfer transfer = onlyTransferOf(run);
        rail.scripting(transfer.railRef(), StubRail.Behaviour.UNREACHABLE);

        readBack(run.run().id());
        clock.advance(BASE.plusSeconds(1));
        readBack(run.run().id());

        rail.scripting(transfer.railRef(), StubRail.Behaviour.CONFIRM);
        clock.advance(Duration.ofSeconds(11));

        // The transfer's wait expired before the breaker's cooldown, so this pass is refused by the breaker.
        readBack(run.run().id());
        assertThat(rail.submissionsOf(transfer.railRef())).isEqualTo(2);

        clock.advance(COOLDOWN.plusSeconds(1));
        ComputedRun executed = readBack(run.run().id());

        assertThat(rail.submissionsOf(transfer.railRef())).as("the probe was allowed through").isEqualTo(3);
        assertThat(executed.run().status()).isEqualTo(RunStatus.DONE);
        assertThat(onlyTransferOf(executed).status()).isEqualTo(TransferStatus.CONFIRMED);

        clock.advance(Duration.ofSeconds(2));
        driveRuns.driveDue(clock.instant().minusSeconds(1), 10);

        assertThat(rail.submissionsOf(transfer.railRef()))
                .as("and a finished run is never offered again")
                .isEqualTo(3);
    }

    /** A single-currency run whose one transfer is ready to move: approved, and nothing to fund. */
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
