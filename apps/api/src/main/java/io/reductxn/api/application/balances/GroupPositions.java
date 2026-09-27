package io.reductxn.api.application.balances;

import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.api.port.out.TaxRuleCatalog;
import io.reductxn.netting.api.NetPosition;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.planning.api.SettlementPlanner;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group's positions as of an instant: the composition, in one place, for the two reads that serve it.
 *
 * <p><strong>It exists because ADR 0055 gave the balances a second reader.</strong> {@link ReadGroupBalances} serves
 * them to a member; {@link ReadSharedViewService} serves them to whoever holds a share link. Both ask the same question
 * of the same ports - the group's open obligations at an instant, netted by the same {@link SettlementPlanner}, with each
 * currency's tax rule resolved the same way - and the alternative, two compositions, is exactly what ADR 0039 refused
 * when it made the balances read the preview's own computation: "a second composition path is how the two answers start
 * to differ". The difference between the two reads is not what they compute. It is who may ask, and that is the caller's
 * business rather than this component's.
 *
 * <p><strong>So it has no condition in it at all, deliberately.</strong> No membership check, no code check, nothing
 * that could be mistaken for authorisation: both conditions live one layer up, where a use case can be read and its
 * guard seen. A component that checked membership would be the wrong shape for the share-link reader, and a component
 * with a flag for which condition applies would be two components pretending to be one.
 *
 * <p>The tax resolver is passed because the plan needs it, and that is a real dependency: the engine assesses tax on the
 * transfers it emits, so a partition with no rule in force cannot be netted at all. A group in that state is not
 * settleable, and both reads say so rather than showing a number whose arithmetic nobody can reproduce.
 */
@Component
class GroupPositions {

    private final OpenObligations openObligations;
    private final TaxRuleCatalog taxRules;
    private final SettlementPlanner planner;

    GroupPositions(OpenObligations openObligations, TaxRuleCatalog taxRules, SettlementPlanner planner) {
        this.openObligations = Objects.requireNonNull(openObligations, "openObligations");
        this.taxRules = Objects.requireNonNull(taxRules, "taxRules");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    /**
     * Every position in this group's open obligations at {@code asOf}, one per member per currency.
     *
     * <p>Ordered as the plan orders them - by currency, then as the engine emitted them - because an order invented here
     * would be a second ordering rule to keep in step with the first. A screen that wants members in alphabetical order
     * sorts its own rows.
     */
    List<NetPosition> of(UUID groupId, Instant asOf) {
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