package io.reductxn.api.application.balances;

import io.reductxn.api.port.in.ReadGroupBalances;
import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.api.port.out.TaxRuleCatalog;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.planning.api.SettlementPlanner;

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
 * come from the same port, the partitioning and the netting are the same {@link SettlementPlanner}, and the
 * positions are the ones the plan carries - so the answer a member reads here is the answer a preview would have
 * given, and cannot drift from it. The alternative was a second composition path that nets the group's obligations
 * for this read alone; that is how one group comes to be told two numbers, which is the failure ADR 0036 named when
 * it made the obligations list and the netting preview share one definition of "open".
 *
 * <p><strong>The tax resolver is passed because the plan needs it, and that is a real dependency</strong> - the
 * engine assesses tax on the transfers it emits, so a partition with no rule in force cannot be netted at all. A
 * group in that state is not settleable, and this read says so rather than showing a number whose arithmetic nobody
 * can reproduce. The contract states this rather than leaving it to be discovered.
 *
 * <p><strong>Nothing is sorted here.</strong> The plan is already ordered (partitions by currency, legs normalised
 * before the engine saw them), and an order invented at this layer would be a second ordering rule to keep in step
 * with the first. A screen that wants members in alphabetical order sorts its own rows.
 */
@Service
public class ReadGroupBalancesService implements ReadGroupBalances {

    private final OpenObligations openObligations;
    private final TaxRuleCatalog taxRules;
    private final SettlementPlanner planner;

    public ReadGroupBalancesService(
            OpenObligations openObligations, TaxRuleCatalog taxRules, SettlementPlanner planner) {

        this.openObligations = Objects.requireNonNull(openObligations, "openObligations");
        this.taxRules = Objects.requireNonNull(taxRules, "taxRules");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    @Transactional(readOnly = true)
    public List<NetPosition> balances(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        SettlementPlan plan = planner.plan(
                openObligations.openFor(groupId, asOf),
                currency -> taxRules.activeFor(currency, asOf));

        return plan.partitions().stream()
                .flatMap(partition -> partition.netting().positions().stream())
                .toList();
    }
}
