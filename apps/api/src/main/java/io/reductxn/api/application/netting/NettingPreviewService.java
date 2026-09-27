package io.reductxn.api.application.netting;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.PreviewNetting;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.api.port.out.TaxRuleCatalog;
import io.reductxn.kernel.api.Result;
import io.reductxn.planning.api.CycleObligation;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.planning.api.SettlementPlanner;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: preview a group's settlement without recording anything.
 *
 * <p><strong>It contains no rules.</strong> Which currencies a cycle spans, how each partition nets, what
 * the tax is and where the FX requirement lands are all answered by {@code libs/planning} and the modules
 * beneath it. What is here is orchestration: read through one port, resolve tax through another, hand both
 * to the planner. A rule that appears in this class is a rule that belongs in {@code libs/*}, and the
 * module README says so.
 *
 * <p>The transaction boundary is here, which is what RULE B15 is about - no {@code @Transactional}
 * anywhere else in the module. A transaction manager does exist now that {@code platform/persistence} is
 * wired: {@code spring-boot-starter-jooq} brings spring-jdbc with it, so Boot's
 * {@code DataSourceTransactionManagerAutoConfiguration} supplies a {@code JdbcTransactionManager} and jOOQ's
 * DSLContext joins the transaction rather than opening its own connection. That was not true when this
 * comment was first written, and it matters beyond tidiness: the record-obligation use case's chain lock is
 * held for the length of the transaction, so a boundary that did not exist would have been a lock that did
 * not either.
 *
 * <p>Resolving tax per currency rather than once is not incidental: a rule is keyed by
 * {@code (jurisdiction, currency, effective date)} (ADR 0006), so a multicurrency cycle resolves it per
 * partition. The adapter from this module's {@link TaxRuleCatalog} to the planner's resolver is the
 * lambda below, and it is where {@code asOf} is bound.
 */
@Service
public class NettingPreviewService implements PreviewNetting {

    private final Groups groups;
    private final OpenObligations openObligations;
    private final TaxRuleCatalog taxRules;
    private final SettlementPlanner planner;

    public NettingPreviewService(
            Groups groups, OpenObligations openObligations, TaxRuleCatalog taxRules, SettlementPlanner planner) {

        this.groups = Objects.requireNonNull(groups, "groups");
        this.openObligations = Objects.requireNonNull(openObligations, "openObligations");
        this.taxRules = Objects.requireNonNull(taxRules, "taxRules");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<SettlementPlan> preview(UUID callerId, UUID groupId, Instant asOf) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        // The membership is the condition since ADR 0054, and it is checked here rather than a line later because the
        // tax resolver below is asked per currency: a stranger who got as far as the plan could learn which currencies
        // this deployment has rules for from the difference between a `404` and a `422` (ADR 0049's ordering).
        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        List<CycleObligation> open = openObligations.openFor(groupId, asOf);

        // `asOf` is bound here rather than passed through, because the planner is pure and may not read a
        // clock (RULE B21). This is the boundary where "as of when" stops being a parameter and becomes a
        // fixed answer.
        return Result.ok(planner.plan(open, currency -> taxRules.activeFor(currency, asOf)));
    }
}
