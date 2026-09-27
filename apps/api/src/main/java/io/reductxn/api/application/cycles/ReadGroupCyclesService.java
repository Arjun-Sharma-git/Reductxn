package io.reductxn.api.application.cycles;

import io.reductxn.api.port.in.CycleReport;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadGroupCycles;
import io.reductxn.api.port.out.Cycles;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

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
 *
 * <p><strong>Membership is the condition since ADR 0054</strong>, and the check is the first statement for the reason
 * the runs list gives. A cycle is the group's own claim about when its books closed, so the read that lists them is a
 * page of the group's decisions rather than an empty list anybody may ask for.
 */
@Service
public class ReadGroupCyclesService implements ReadGroupCycles {

    /** The most cycles one page may hold, for the reason {@code ReadGroupRunsService.MAX_RUNS} gives. */
    static final int MAX_CYCLES = 100;

    private final Groups groups;
    private final Cycles cycles;

    public ReadGroupCyclesService(Groups groups, Cycles cycles) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.cycles = Objects.requireNonNull(cycles, "cycles");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<CycleReport>> list(UUID callerId, UUID groupId, int limit) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");

        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        return Result.ok(cycles.listByGroup(groupId, Math.min(Math.max(limit, 1), MAX_CYCLES)));
    }
}
