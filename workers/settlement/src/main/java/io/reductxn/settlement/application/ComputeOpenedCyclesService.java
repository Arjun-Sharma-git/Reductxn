package io.reductxn.settlement.application;

import io.reductxn.kernel.api.Result;
import io.reductxn.settlement.port.in.ComputeOpenedCycles;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedCycles;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.out.Runs;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Computes the runs for the cycles somebody has opened.
 *
 * <p>Thin on purpose (see {@link ComputeOpenedCycles}): the query finds the cycles, {@link ComputeRun} does every
 * part of the work that matters, and this class decides only which cycles get it, in what order, and what a report
 * says afterwards.
 *
 * <p><strong>Not {@code @Transactional}, and it must not be.</strong> Each {@code compute} is one transaction,
 * through the port's own proxy - a plan is all-or-nothing, which is why the use case is annotated - and a pass
 * wrapped in a transaction of its own would make one cycle's fault roll back every plan the pass had already
 * written. The same shape as {@code DriveRunsService}, for the same reason: a batch is a sequence of commits
 * rather than one, so a pass that dies leaves the cycles it finished done.
 *
 * <p><strong>A fault is contained to its cycle and reported, which is not what {@code DriveRunsService}
 * does.</strong> The difference is what the two passes wrap: driving calls {@code ExecuteRun}, whose failures are
 * refusals by design and whose writes are one transfer at a time, so a fault there is about the pass itself.
 * Computing calls the planner over a group's whole book, and the interesting fault - a currency with no active tax
 * rule, a group whose rows are inconsistent - belongs to one cycle. Letting it propagate would stop the batch
 * behind it for ever, because the cycle stays in the query: the pass would retry, fault, and never reach the
 * cycles after it. So it is caught, named in the report, and the pass continues.
 */
public class ComputeOpenedCyclesService implements ComputeOpenedCycles {

    /** What a pass may attempt when the caller has not said, so no caller can forget to bound it. */
    public static final int DEFAULT_BATCH_SIZE = 50;

    private final Runs runs;
    private final ComputeRun computeRun;

    public ComputeOpenedCyclesService(Runs runs, ComputeRun computeRun) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.computeRun = Objects.requireNonNull(computeRun, "computeRun");
    }

    @Override
    public ComputedCycles computeOpened(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "a pass computes at least one cycle or does nothing at all, but the batch size was "
                            + batchSize + ". A computer told to consider nothing would look like a worker that is "
                            + "broken rather than one that is idle");
        }

        List<Runs.OpenedCycle> opened = runs.cyclesWithoutRuns(batchSize);
        List<ComputedCycles.CycleOutcome> outcomes = new ArrayList<>(opened.size());

        for (Runs.OpenedCycle cycle : opened) {
            outcomes.add(compute(cycle));
        }

        return new ComputedCycles(outcomes);
    }

    /**
     * One cycle: the same request an operator's command would make, and the same refusal when there is nothing to
     * settle.
     *
     * <p>Nothing is recorded about the attempt, deliberately. There is no column for it, and inventing one would
     * be a second place where "this cycle cannot be computed" is written down - the refusal already says it, and
     * the cycle's own absence of a run is what the next pass reads. What that costs is stated in the port: a
     * cycle that cannot be computed is looked at again every pass until something changes, which is also how a
     * backdated obligation can still be settled by the cycle it belongs to.
     */
    private ComputedCycles.CycleOutcome compute(Runs.OpenedCycle cycle) {
        try {
            Result<ComputedRun> computed = computeRun.compute(new RunRequest(cycle.groupId(), cycle.asOf()));

            if (computed instanceof Result.Failure<ComputedRun> refusal) {
                return new ComputedCycles.CycleOutcome(
                        cycle.cycleId(), cycle.groupId(), cycle.asOf(), null, null,
                        refusal.error().code() + ": " + refusal.error().message(), null);
            }

            ComputedRun run = computed.orElseThrow();

            return new ComputedCycles.CycleOutcome(
                    cycle.cycleId(), cycle.groupId(), cycle.asOf(), run.run().id(), run.run().status(),
                    null, null);
        } catch (RuntimeException fault) {
            return new ComputedCycles.CycleOutcome(
                    cycle.cycleId(), cycle.groupId(), cycle.asOf(), null, null, null,
                    fault.getClass().getName() + ": " + fault.getMessage());
        }
    }
}
