package io.reductxn.api.application.runs;

import io.reductxn.api.port.in.ReadRun;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.SettlementRuns;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: read a run, or say there is none.
 *
 * <p><strong>A use case this thin is the point rather than an accident.</strong> Everything a member can be told
 * about a run is already in rows the worker wrote, so this class has one decision to make - does the run exist -
 * and no questions to answer about what the rows mean. The alternative shape, a service that assembled a run's
 * semantics here (is quorum met, what does the group still owe, what is the tax saving worth), would put the run's
 * arithmetic in the module that does not own it; the worker would then have to be changed twice whenever a rule
 * changed, and the two copies would disagree in the intermediate state (ADR 0021).
 *
 * <p>{@code @Transactional} for a read, which is not ceremony: the projection is four reads of four tables, and
 * without one transaction a run could be reported as {@code executing} while its transfers still read as
 * {@code pending} - two instants stitched into a picture that was never true.
 */
@Service
public class ReadRunService implements ReadRun {

    private final SettlementRuns runs;

    public ReadRunService(SettlementRuns runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<RunReport> read(UUID runId) {
        Objects.requireNonNull(runId, "runId");

        Optional<RunReport> run = runs.findById(runId);

        return run.<Result<RunReport>>map(Result::ok)
                .orElseGet(() -> Result.fail(new RunRefusals.NoSuchRun(runId)));
    }
}
