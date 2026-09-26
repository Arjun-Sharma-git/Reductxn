package io.reductxn.settlement.adapter.schedule;

import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.DrivenRuns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The cadence: what makes this worker a worker rather than a library with tests.
 *
 * <p>Until this class existed, nothing triggered anything. A run reached {@code awaiting_approval} and stopped
 * there; the only thing that ever moved it on was a test calling {@code ExecuteRun}. A deployment would compute
 * plans, record approvals, accept currency - and settle nothing, because the last step was waiting for a caller
 * that did not exist.
 *
 * <p><strong>The cadence and the window are settings, not policy.</strong> Nothing about a settlement says how
 * often to look at it; how often to look is a rate limit on rail calls and database round trips, so it is
 * configuration with documented defaults rather than a constant in code. The window is the anti-stampede guard
 * ({@link DriveRuns} takes the cut-off it implies), and the batch size bounds one tick so a backlog drains over
 * several minutes instead of in one hour-long pass.
 *
 * <p><strong>An exception must not end the cadence.</strong> A driver whose database is unreachable - or whose
 * rail throws something nobody classified - would otherwise take the whole worker down with it, and a worker that
 * restarted itself in a loop would settle nothing either. So each tick catches, logs at warn and returns: the
 * next tick is a minute away and the run is still there. This is deliberately the opposite of the module's habit
 * of letting faults surface, because {@code @Scheduled} surfaces a fault by *ending the schedule* - silence is
 * the one outcome a driver must not produce.
 *
 * <p><strong>It is switched on by default and can be switched off</strong>
 * ({@code reductxn.settlement.drive.enabled}), because there are two honest ways to run this worker: as a daemon,
 * or as a scheduled job that runs the command line and exits -
 * {@code --reductxn.command=drive}. Running both at once would double the rail traffic for no benefit, so a
 * deployment chooses. The tests switch it off and drive the port themselves: a scheduler firing in the middle of
 * a test would drive exactly the run the test is halfway through asserting on.
 */
@Component
@ConditionalOnProperty(name = "reductxn.settlement.drive.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledRunDriver {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRunDriver.class);

    private final DriveRuns driveRuns;
    private final Clock clock;
    private final Duration window;
    private final int batchSize;

    public ScheduledRunDriver(
            DriveRuns driveRuns,
            Clock clock,
            @Value("${reductxn.settlement.drive.window:PT2M}") Duration window,
            @Value("${reductxn.settlement.drive.batch-size:50}") int batchSize) {

        this.driveRuns = driveRuns;
        this.clock = clock;
        this.window = window;
        this.batchSize = batchSize;
    }

    /**
     * One tick.
     *
     * <p>{@code initialDelayString} rather than an immediate first run: a worker starting up should finish
     * starting up - Flyway, the pool, the rail client - before it begins offering money movements to a rail, and
     * one interval of patient startup costs nothing. It also means a crash-looping worker does not fire a burst
     * of rail calls on every restart.
     */
    @Scheduled(
            initialDelayString = "${reductxn.settlement.drive.interval:PT1M}",
            fixedDelayString = "${reductxn.settlement.drive.interval:PT1M}")
    void drive() {
        Instant attemptedBefore = clock.instant().minus(window);

        try {
            DrivenRuns report = driveRuns.driveDue(attemptedBefore, batchSize).orElseThrow();

            // Logged only when there is something to say. A cadence that ticks every minute on an idle
            // deployment would otherwise write 1,440 lines a day to report that nothing happened, which is how
            // a log stops being read.
            if (!report.outcomes().isEmpty()) {
                log.info("drove {} run(s): {} moved, {} refused, {} completed, {} failed (window {}, cut-off {})",
                        report.outcomes().size(), report.driven(), report.refused(),
                        report.completed().size(), report.failed().size(), window, attemptedBefore);

                for (DrivenRuns.RunOutcome outcome : report.outcomes()) {
                    log.info("  run {} ({}) {}", outcome.runId(), outcome.status(),
                            outcome.driven() ? "driven" : "left alone: " + outcome.reason());
                }
            }
        } catch (RuntimeException fault) {
            log.warn("the settlement driver could not complete a pass. Nothing was rolled back - each write it "
                    + "made is committed on its own - and the next pass will look at the same runs (cut-off {})",
                    attemptedBefore, fault);
        }
    }
}
