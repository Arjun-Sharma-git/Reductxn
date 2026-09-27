package io.reductxn.settlement.adapter.schedule;

import io.reductxn.settlement.port.in.OpenDueCycles;
import io.reductxn.settlement.port.in.OpenedCycles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * The cadence that starts the pipeline: it opens the cycles a group's schedule has brought due (ADR 0057).
 *
 * <p><strong>The worker now has three stages, and this is the one that was missing.</strong> This component opens cycles
 * from the schedules groups state; {@code ScheduledRunComputer} computes a run for every cycle that has none; the
 * driver settles what was computed. Each is a separate tick with its own interval, because they are different rates of
 * work: opening a cycle is one insert per due group, computing a run is a plan over that group's obligations, and
 * driving is a call to a payment rail. Wiring them into one pass would make the slowest of the three set the pace of the
 * other two - a rail having a bad minute would stop cycles being opened at all.
 *
 * <p><strong>Its own interval, defaulting to a minute, and its own switch.</strong> The settlement interval is a rate
 * limit on rail calls and this one is a scan of a small table, so they are configured apart
 * ({@code reductxn.settlement.schedules.*}); and a deployment running the command line rather than a daemon turns all
 * three off the same way.
 *
 * <p><strong>A fault is logged and swallowed, like the other two.</strong> {@code @Scheduled} ends the schedule when a
 * tick throws, and a worker that stopped opening cycles because one group's row was unreadable would leave every group's
 * books unclosed until somebody restarted it. The next tick is a minute away and the row is still there.
 *
 * <p>Logged only when there is something to say, for the reason the driver's own tick gives: a line a minute reporting
 * that nothing happened is how a log stops being read.
 */
@Component
@ConditionalOnProperty(
        name = "reductxn.settlement.schedules.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledCycleOpener {

    private static final Logger log = LoggerFactory.getLogger(ScheduledCycleOpener.class);

    private final OpenDueCycles openDueCycles;
    private final Clock clock;
    private final int batchSize;

    public ScheduledCycleOpener(
            OpenDueCycles openDueCycles,
            Clock clock,
            @Value("${reductxn.settlement.schedules.batch-size:50}") int batchSize) {

        this.openDueCycles = openDueCycles;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One tick.
     *
     * <p>{@code initialDelayString} for the reason the driver gives: a worker should finish starting up - Flyway, the
     * pool, the rail client - before it begins writing, and a crash-looping worker should not open a burst of cycles on
     * every restart.
     */
    @Scheduled(
            initialDelayString = "${reductxn.settlement.schedules.interval:PT1M}",
            fixedDelayString = "${reductxn.settlement.schedules.interval:PT1M}")
    void openDueCycles() {
        Instant now = clock.instant();

        try {
            OpenedCycles report = openDueCycles.openDue(now, batchSize);

            if (!report.outcomes().isEmpty()) {
                log.info("{} schedule(s) were due at or before {}: {} cycle(s) opened", report.outcomes().size(), now,
                        report.opened());

                for (OpenedCycles.Outcome outcome : report.outcomes()) {
                    log.info("  group {} closed at {} ({}) and its next closure is {} ({})",
                            outcome.groupId(), outcome.dueAt(),
                            outcome.opened() ? "opened" : "already open",
                            outcome.nextDueAt(),
                            outcome.advanced() ? "moved" : "moved by another pass");
                }
            }
        } catch (RuntimeException fault) {
            log.warn("the schedule opener could not complete a pass. Nothing was rolled back - each write it made is "
                    + "committed on its own - and the next pass will find the same schedules (at {})", now, fault);
        }
    }
}