package io.reductxn.settlement.application;

import io.reductxn.settlement.port.in.Escalations;
import io.reductxn.settlement.port.in.ReadEscalations;
import io.reductxn.settlement.port.in.SilentTransfer;
import io.reductxn.settlement.port.out.Runs;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What no rail will decide, as a report.
 *
 * <p><strong>The thinnest service in this module, and deliberately so.</strong> Escalation is a policy about time
 * ({@link Escalation}) and a question about rows ({@link Runs#findSilentTransfers}), so there is nothing left for a
 * use case to decide - which is the point: a service that also judged would be a second place where "is this
 * transfer stuck" is answered, and the answer is the comparison the query already makes.
 *
 * <p>It is a class rather than the adapter reading the port directly because the two answers a report needs - which
 * transfers, and how many in total - come from two queries, and a caller that had to remember both would be a
 * caller that eventually reported a truncated list as the whole problem. The pairing lives here.
 */
public class ReadEscalationsService implements ReadEscalations {

    private final Runs runs;

    public ReadEscalationsService(Runs runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    public Escalations silentLongerThan(Instant cutOff, int limit) {
        Objects.requireNonNull(cutOff, "cutOff");

        if (limit < 1) {
            throw new IllegalArgumentException(
                    "a report that lists " + limit + " transfer(s) is not a report. The limit exists so a broken "
                            + "rail cannot turn a log line into a novel, not to hide the problem");
        }

        List<SilentTransfer> silent = runs.findSilentTransfers(cutOff, limit);
        int total = runs.countSilentTransfers(cutOff);

        return new Escalations(cutOff, Math.max(total, silent.size()), silent);
    }
}
