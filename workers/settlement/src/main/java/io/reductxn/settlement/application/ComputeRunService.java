package io.reductxn.settlement.application;

import io.reductxn.kernel.api.Result;
import io.reductxn.planning.api.CycleObligation;
import io.reductxn.planning.api.SettlementPlan;
import io.reductxn.planning.api.SettlementPlanner;
import io.reductxn.planning.api.TaxRuleResolver;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.RunRefusals;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.in.RunStatus;
import io.reductxn.settlement.port.in.SettlementRun;
import io.reductxn.settlement.port.out.OpenObligations;
import io.reductxn.settlement.port.out.OpenObligations.OpenCommitment;
import io.reductxn.settlement.port.out.Runs;
import io.reductxn.settlement.port.out.TaxRuleCatalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Opening a run for a cycle and recording the plan it will execute.
 *
 * <p>The whole use case, and it is short because the decisions are elsewhere: the plan is
 * {@code libs/planning}'s (RULE B21), the obligations and the tax rules are read through ports, and the run's
 * status is the one thing decided here.
 *
 * <p>Nothing schedules this. The tests call it, and a scheduler or an operator's command is a later slice.
 * Saying so is better than a comment that implies a trigger exists.
 */
@Service
public class ComputeRunService implements ComputeRun {

    private final Runs runs;
    private final OpenObligations obligations;
    private final TaxRuleCatalog taxRules;
    private final SettlementPlanner planner;
    private final Clock clock;

    public ComputeRunService(
            Runs runs,
            OpenObligations obligations,
            TaxRuleCatalog taxRules,
            SettlementPlanner planner,
            Clock clock) {

        this.runs = Objects.requireNonNull(runs, "runs");
        this.obligations = Objects.requireNonNull(obligations, "obligations");
        this.taxRules = Objects.requireNonNull(taxRules, "taxRules");
        this.planner = Objects.requireNonNull(planner, "planner");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The use case.
     *
     * <p><strong>The retry is checked first, and it is the ordinary case rather than an edge.</strong> A caller
     * that timed out does not know whether the run was opened, so a second attempt must be a read: the cycle is
     * keyed by {@code (group, as-of)}, the ids are derived from that pair, and the database holds a unique
     * constraint on the pair as well. A duplicate plan is impossible in both places rather than unlikely in one.
     *
     * <p><strong>The status is decided here, not in the adapter.</strong> Whether a group can settle is a rule
     * about the group's money (RULE S4), not about how a row is written: a partition whose {@code fx_clearing}
     * position is non-zero means the group promised a currency it does not hold, so the run waits for funding
     * rather than for approval. An adapter that chose the status would put that rule behind a repository's method
     * signature, where nothing can test it without a database.
     */
    @Override
    @Transactional
    public Result<ComputedRun> compute(RunRequest request) {

        Optional<ComputedRun> alreadyOpen = runs.findRun(request.groupId(), request.asOf());

        if (alreadyOpen.isPresent()) {
            return Result.ok(alreadyOpen.get());
        }

        List<OpenCommitment> cycle = obligations.openFor(request.groupId(), request.asOf());

        if (cycle.isEmpty()) {
            return Result.fail(new RunRefusals.NothingToSettle(request.groupId(), request.asOf()));
        }

        // The planner consumes the commitments; the run records their identities. Both come from the same
        // rows, read once: a run has to be able to say which promises it settled, or a later cycle would net
        // them again and the group would move money twice for one debt.
        List<CycleObligation> commitments = cycle.stream().map(OpenCommitment::obligation).toList();
        List<UUID> settledObligationIds = cycle.stream().map(OpenCommitment::obligationId).toList();

        // Effective-dated and per currency: the rule is resolved at the cycle's instant rather than at now, so a
        // run over a past cycle is assessed under the law that applied then (ADR 0006). Handed over as a function
        // rather than as a map this layer walks, because which currencies a cycle touches is the planner's answer
        // and not something a use case should have to work out first.
        TaxRuleResolver resolver = currency -> taxRules.activeFor(currency, request.asOf());
        SettlementPlan plan = planner.plan(commitments, resolver);

        Instant now = clock.instant();

        SettlementRun run = new SettlementRun(
                runIdOf(request.groupId(), request.asOf()),
                request.groupId(),
                cycleIdOf(request.groupId(), request.asOf()),
                plan.requiresExternalFunding() ? RunStatus.AWAITING_FX_FUNDING : RunStatus.AWAITING_APPROVAL,
                request.asOf(),
                now,
                now);

        return Result.ok(runs.open(run, plan, settledObligationIds));
    }

    /**
     * The cycle's identity, derived from the pair that keys it.
     *
     * <p>Derived rather than random, for the reason the quote's id is: a retry must collide rather than
     * duplicate, and a database that arbitrates collisions is a stronger guarantee than a check in a use case.
     * The unique constraint on {@code (group_id, as_of)} covers what this derivation cannot - two processes
     * computing at once - and the loser of that race finds the winner's run on its next attempt, which is what
     * makes the retry path above a recovery rather than a wish.
     */
    static UUID cycleIdOf(UUID groupId, Instant asOf) {
        return UUID.nameUUIDFromBytes((groupId + ":cycle:" + asOf).getBytes(StandardCharsets.UTF_8));
    }

    /** The run's identity, derived from its cycle: one run per cycle, by construction rather than by check. */
    static UUID runIdOf(UUID groupId, Instant asOf) {
        return UUID.nameUUIDFromBytes((groupId + ":run:" + asOf).getBytes(StandardCharsets.UTF_8));
    }
}

