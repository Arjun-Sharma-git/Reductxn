package io.reductxn.settlement.adapter.schedule;

import io.reductxn.settlement.port.in.ComputeOpenedCycles;
import io.reductxn.settlement.port.in.ComputedCycles;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The other cadence: computing what has been opened.
 *
 * <p><strong>{@link ScheduledRunDriver} moves money; this one reads a book.</strong> That is the whole reason they
 * are two classes rather than one tick. Driving is bounded by rail calls - it may not offer a rail more than the
 * window allows - and computing is bounded by the planner, which is a read and a write inside one transaction.
 * Folding them together would tie the rate of rail traffic to the rate of planning, and neither rate is a
 * consequence of the other.
 *
 * <p><strong>Why it exists.</strong> A cycle and its plan used to be written in one transaction, so there was
 * never a cycle waiting to be computed and nothing to trigger: an operator who wanted a settlement computed it,
 * and a group that wanted one computed nothing. Now that a cycle can be opened on its own - which is what a
 * settlement policy decides, and the policy itself is still the group's, still open in the plan - something has
 * to notice. A worker whose only answer to an opened cycle was a log line would settle nothing while looking
 * perfectly healthy, and the person waiting for their group's settlement has no way to tell the difference from
 * outside.
 *
 * <p><strong>The cadence and the batch are settings, not policy.</strong> Nothing about a settlement says how
 * soon a plan must exist; one minute is a staleness a member can watch (they asked, they are looking at a list),
 * and it costs one indexed query per minute on an idle deployment - the cheapest thing this worker does. The batch
 * size bounds one tick so a backlog drains over several passes instead of one long one.
 *
 * <p><strong>Switched on by default, and switched off by the same reasoning as the driver's
 * ({@code reductxn.settlement.compute.enabled}).</strong> A deployment runs this worker as a daemon
 * ({@code compute-opened} on the cadence) or as a scheduled job that runs the command line and exits, and the
 * tests switch it off so a tick cannot compute the cycle a test is halfway through opening.
 *
 * <p><strong>An exception must not end the cadence</strong>, for the reason the driver's javadoc gives: a
 * {@code @Scheduled} fault ends the schedule, and silence is the one outcome a cadence must not produce. Each
 * cycle's own fault is already contained by the use case and reported in the pass's outcome, so what is caught
 * here is the query itself.
 */
@Component
@ConditionalOnProperty(name = "reductxn.settlement.compute.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledRunComputer {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRunComputer.class);

    private final ComputeOpenedCycles computeOpenedCycles;
    private final int batchSize;

    public ScheduledRunComputer(
            ComputeOpenedCycles computeOpenedCycles,
            @Value("${reductxn.settlement.compute.batch-size:50}") int batchSize) {

        this.computeOpenedCycles = computeOpenedCycles;
        this.batchSize = batchSize;
    }

    /**
     * One tick.
     *
     * <p>{@code initialDelayString} for the driver's reason: a worker should finish starting up - Flyway, the
     * pool, the planner's dependencies - before it begins reading a group's whole book, and one interval of
     * patient startup costs nothing.
     */
    @Scheduled(
            initialDelayString = "${reductxn.settlement.compute.interval:PT1M}",
            fixedDelayString = "${reductxn.settlement.compute.interval:PT1M}")
    void compute() {
        try {
            ComputedCycles report = computeOpenedCycles.computeOpened(batchSize);

            // Logged only when there is something to say, and a refusal is not something to say: a cycle a group
            // has not filled yet is looked at every pass, so logging it every minute would be the same line for
            // ever. The command line shows the whole report on demand.
            if (report.computed() > 0 || report.faulted() > 0) {
                log.info("computed {} of {} opened cycle(s): {} run(s), {} refused, {} failed (batch {})",
                        report.computed(), report.outcomes().size(), report.computed(),
                        report.refused(), report.faulted(), batchSize);

                for (ComputedCycles.CycleOutcome outcome : report.outcomes()) {
                    if (outcome.faulted()) {
                        log.warn("  cycle {} of group {} could not be computed: {}",
                                outcome.cycleId(), outcome.groupId(), outcome.fault());
                    } else if (outcome.computed()) {
                        log.info("  cycle {} of group {} is run {} ({})",
                                outcome.cycleId(), outcome.groupId(), outcome.runId(), outcome.status());
                    }
                }
            }
        } catch (RuntimeException fault) {
            log.warn("the settlement computer could not complete a pass. Each cycle it computed is committed on "
                    + "its own, and the next pass will look at the same cycles (batch {})", batchSize, fault);
        }
    }
}
