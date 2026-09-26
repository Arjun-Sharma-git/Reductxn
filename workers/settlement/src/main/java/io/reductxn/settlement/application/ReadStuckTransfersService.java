package io.reductxn.settlement.application;

import io.reductxn.settlement.port.in.ReadStuckTransfers;
import io.reductxn.settlement.port.in.StuckTransfer;
import io.reductxn.settlement.port.in.StuckTransfers;
import io.reductxn.settlement.port.out.Runs;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What no rail will decide, as a report.
 *
 * <p><strong>The thinnest service in this module, and deliberately so.</strong> Escalation is two policies about time
 * ({@code Escalation} and {@code Reconciliation}) and a question about rows ({@link Runs#findStuckTransfers}), so
 * there is nothing left for a use case to decide - which is the point: a service that also judged would be a second
 * place where "is this transfer stuck" is answered, and the answer is the comparison the two policies' cut-offs
 * already make in the query.
 *
 * <p>It is a class rather than the adapter reading the port directly because the two answers a report needs - which
 * transfers, and how many in total - come from two queries, and a caller that had to remember both would be a caller
 * that eventually reported a truncated list as the whole problem. The pairing lives here. The same argument is why
 * the two reasons live in one report rather than two: an operator should not have to combine two answers to get the
 * one they asked for (ADR 0026).
 */
public class ReadStuckTransfersService implements ReadStuckTransfers {

    private final Runs runs;

    public ReadStuckTransfersService(Runs runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    public StuckTransfers stuckLongerThan(Instant unansweredCutOff, Instant unconfirmedCutOff, int limit) {
        Objects.requireNonNull(unansweredCutOff, "unansweredCutOff");
        Objects.requireNonNull(unconfirmedCutOff, "unconfirmedCutOff");

        if (limit < 1) {
            throw new IllegalArgumentException(
                    "a report that lists " + limit + " transfer(s) is not a report. The limit exists so a broken "
                            + "rail cannot turn a log line into a novel, not to hide the problem");
        }

        List<StuckTransfer> stuck = runs.findStuckTransfers(unansweredCutOff, unconfirmedCutOff, limit);
        int total = runs.countStuckTransfers(unansweredCutOff, unconfirmedCutOff);

        return new StuckTransfers(unansweredCutOff, unconfirmedCutOff, Math.max(total, stuck.size()), stuck);
    }
}
