package io.reductxn.api.application.balances;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadGroupBalances;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;
import io.reductxn.netting.api.NetPosition;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: a group's balances, which are the positions of the plan it would settle today.
 *
 * <p><strong>It is the preview's computation, minus the parts nobody asked for</strong> (ADR 0039). The obligations
 * come from the same port, the partitioning and the netting are the same {@code SettlementPlanner}, and the
 * positions are the ones the plan carries - so the answer a member reads here is the answer a preview would have
 * given, and cannot drift from it. The alternative was a second composition path that nets the group's obligations
 * for this read alone; that is how one group comes to be told two numbers, which is the failure ADR 0036 named when
 * it made the obligations list and the netting preview share one definition of "open".
 *
 * <p><strong>The composition itself moved into {@link GroupPositions} when the share link arrived</strong> (ADR 0055),
 * and this class is what stayed: the membership check and the answer. That split is the point - the two reads that serve
 * a group's positions differ in *who may ask*, and the only way to see that difference is for each of them to be the
 * thing that decides it. What this one decides is ADR 0054: a member of the group, checked before anything is read.
 *
 * <p>The tax resolver is needed by the plan and is therefore {@code GroupPositions}' dependency rather than this
 * class's, which is where that argument lives now.
 *
 * <p><strong>Nothing is sorted here.</strong> The plan is already ordered, and {@code GroupPositions} says so.
 *
 * <p><strong>The membership check comes first, and it is the first statement in the method rather than a clause
 * somewhere below</strong> (ADR 0054). Two things depend on that order and both are concrete: a stranger must not be
 * able to read a position before being refused, and a stranger must not learn which currencies this deployment has tax
 * rules for by comparing a `404` against a `422` - which is the ordering ADR 0049 established for recording an
 * obligation, applied to the read that nets them.
 */
@Service
public class ReadGroupBalancesService implements ReadGroupBalances {

    private final Groups groups;
    private final GroupPositions positions;

    public ReadGroupBalancesService(Groups groups, GroupPositions positions) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.positions = Objects.requireNonNull(positions, "positions");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<NetPosition>> balances(UUID callerId, UUID groupId, Instant asOf) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        // Before the obligations and before the tax rules: a balance is the group's, and a refusal that came after the
        // plan was computed would be a refusal that had already done the work it is refusing to show.
        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        return Result.ok(positions.of(groupId, asOf));
    }
}
