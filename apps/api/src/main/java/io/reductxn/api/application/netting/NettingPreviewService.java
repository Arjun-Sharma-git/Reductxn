package io.reductxn.api.application.netting;

import io.reductxn.api.port.in.PreviewNetting;
import io.reductxn.api.port.out.OpenObligations;
import io.reductxn.api.port.out.TaxRuleCatalog;
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
 * anywhere else in the module. Note that no transaction manager exists yet: it arrives with
 * {@code platform/persistence}, so this annotation currently marks the boundary rather than enforcing it.
 * It is written now because moving boundaries afterwards is a rewrite, and because the rule that keeps
 * them in one layer is checkable today.
 *
 * <p>Resolving tax per currency rather than once is not incidental: a rule is keyed by
 * {@code (jurisdiction, currency, effective date)} (ADR 0006), so a multicurrency cycle resolves it per
 * partition. The adapter from this module's {@link TaxRuleCatalog} to the planner's resolver is the
 * lambda below, and it is where {@code asOf} is bound.
 */
@Service
public class NettingPreviewService implements PreviewNetting {

    private final OpenObligations openObligations;
    private final TaxRuleCatalog taxRules;
    private final SettlementPlanner planner;

    public NettingPreviewService(
            OpenObligations openObligations, TaxRuleCatalog taxRules, SettlementPlanner planner) {

        this.openObligations = Objects.requireNonNull(openObligations, "openObligations");
        this.taxRules = Objects.requireNonNull(taxRules, "taxRules");
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    @Override
    @Transactional(readOnly = true)
    public SettlementPlan preview(UUID groupId, Instant asOf) {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(asOf, "asOf");

        List<CycleObligation> open = openObligations.openFor(groupId, asOf);

        // `asOf` is bound here rather than passed through, because the planner is pure and may not read a
        // clock (RULE B21). This is the boundary where "as of when" stops being a parameter and becomes a
        // fixed answer.
        return planner.plan(open, currency -> taxRules.activeFor(currency, asOf));
    }
}
