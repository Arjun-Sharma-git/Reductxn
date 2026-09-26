package io.reductxn.api.application.runs;

import io.reductxn.api.port.in.ReadGroupRuns;
import io.reductxn.api.port.in.RunReport;
import io.reductxn.api.port.out.SettlementRuns;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's runs, newest first.
 *
 * <p>The thinnest service in this module, and it still owns one decision: <strong>how many rows a list may
 * return.</strong> That is not a detail of the contract's query string - a client can send any number - so the
 * ceiling lives here, where a rule about this module's own reads belongs, and the contract's default lives in the
 * controller, where the wire format is decided. Without a ceiling the endpoint is an unbounded read whose cost
 * grows with a group's history, and the first group to reach a few thousand cycles would find out.
 *
 * <p>{@code @Transactional(readOnly = true)} for the reason {@link ReadRunService} gives, and it matters more here:
 * a listed run is four projections, so a list of twenty is eighty reads, and without one transaction two runs in the
 * same list could be reported at two different instants - a page that never existed.
 */
@Service
public class ReadGroupRunsService implements ReadGroupRuns {

    /**
     * The most runs one page may hold.
     *
     * <p>A hundred rather than a hundred thousand for a stated reason: a group settles a cycle at a time, so a page
     * of a hundred runs is more history than any screen shows at once, and a member who needs older ones needs a
     * *cursor* - which is what the contract's other list endpoints use and what this one will need when a group has
     * more than a hundred cycles. Claiming paging now without a cursor would be a limit pretending to be paging.
     */
    static final int MAX_RUNS = 100;

    private final SettlementRuns runs;

    public ReadGroupRunsService(SettlementRuns runs) {
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunReport> list(UUID groupId, int limit) {
        Objects.requireNonNull(groupId, "groupId");

        return runs.listByGroup(groupId, Math.min(Math.max(limit, 1), MAX_RUNS));
    }
}
