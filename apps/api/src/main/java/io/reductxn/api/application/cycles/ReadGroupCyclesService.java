package io.reductxn.api.application.cycles;

import io.reductxn.api.port.in.CycleReport;
import io.reductxn.api.port.in.ReadGroupCycles;
import io.reductxn.api.port.out.Cycles;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's cycles, newest first.
 *
 * <p>As thin as {@code ReadGroupRunsService}, and it owns the same one decision: <strong>how many rows a list may
 * return.</strong> The ceiling is a rule about this module's own reads, so it lives here; the contract's default
 * lives in the controller, where the wire format is decided.
 *
 * <p>Unlike the runs list this is one query rather than four per row - a cycle is a row, and its plan is a nullable
 * id joined to it - so it does not need the transaction that read does for consistency across rows. It reads one
 * table plus a lookup into the worker's runs, and {@code readOnly} says so to the connection rather than as a
 * promise about isolation.
 */
@Service
public class ReadGroupCyclesService implements ReadGroupCycles {

    /** The most cycles one page may hold, for the reason {@code ReadGroupRunsService.MAX_RUNS} gives. */
    static final int MAX_CYCLES = 100;

    private final Cycles cycles;

    public ReadGroupCyclesService(Cycles cycles) {
        this.cycles = Objects.requireNonNull(cycles, "cycles");
    }

    @Override
    @Transactional(readOnly = true)
    public List<CycleReport> list(UUID groupId, int limit) {
        Objects.requireNonNull(groupId, "groupId");

        return cycles.listByGroup(groupId, Math.min(Math.max(limit, 1), MAX_CYCLES));
    }
}
