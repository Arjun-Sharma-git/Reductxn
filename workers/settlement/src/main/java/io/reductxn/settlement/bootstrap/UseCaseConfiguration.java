package io.reductxn.settlement.bootstrap;

import io.reductxn.planning.api.SettlementPlanner;
import io.reductxn.planning.api.SettlementPlans;
import io.reductxn.rails.api.BreakerPolicy;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.rails.api.Rails;
import io.reductxn.settlement.application.Backoff;
import io.reductxn.settlement.application.DriveRunsService;
import io.reductxn.settlement.application.ReadStuckTransfersService;
import io.reductxn.settlement.application.ResolveTransferService;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.Escalation;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.in.ReadStuckTransfers;
import io.reductxn.settlement.port.in.Reconciliation;
import io.reductxn.settlement.port.in.ResolveTransfer;
import io.reductxn.settlement.port.out.ChainHeads;
import io.reductxn.settlement.port.out.JournalEntries;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

/**
 * Spring wiring only. No logic lives in this package - a class here that makes a decision is a class in the
 * wrong package.
 *
 * <p>Explicit beans rather than components found by scanning, so what this module depends on is readable in one
 * file and every port has a named implementation.
 */
@Configuration
public class UseCaseConfiguration {

    /**
     * The planner, as a bean rather than a static call at each use site.
     *
     * <p>The same instance {@code apps/api} wires, from the same factory, which is the point of
     * {@code libs/planning}: a preview and a run must compute the same plan, and two planners would be two
     * answers.
     */
    @Bean
    SettlementPlanner settlementPlanner() {
        return SettlementPlans.perCurrency();
    }

    /**
     * The clock, and the only one in this module.
     *
     * <p>Nothing below {@code application} may read a clock, and the use case takes its instant from its caller
     * rather than from here: this is used for the timestamps of the rows a run writes, and nothing else. The
     * distinction matters because a cycle's cut-off is a decision the caller makes - a run that read the clock
     * itself could not settle a past cycle, and could not be tested without one.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The driver: what moves the runs nothing else moves.
     *
     * <p>A bean here rather than a component on the class, so the layer rule stays visible: the service is in
     * {@code application}, and the two things that trigger it - the cadence in {@code adapter/schedule} and the
     * command in {@code adapter/command} - are the only callers, and both reach it through
     * {@link io.reductxn.settlement.port.in.DriveRuns} rather than through this class.
     *
     * <p>One instance of the clock is shared with every other use case, which is what keeps the driver's mark and
     * a transfer's authorisation on the same timeline.
     */
    @Bean
    DriveRuns driveRuns(
            Runs runs, ExecuteRun executeRun, Clock clock, PlatformTransactionManager transactionManager) {

        return new DriveRunsService(runs, executeRun, clock, transactionManager);
    }

    /**
     * The rail this worker submits settlement transfers to, with a circuit breaker in front of it.
     *
     * <p><strong>Named by configuration rather than chosen in code, and unknown names fail at startup.</strong> A
     * deployment that asked for a rail this build does not have must not silently get a different one: the only
     * way to move money here is through this bean, so a fallback would mean money moving somewhere nobody
     * configured. The default is {@code mock}, which moves nothing and confirms everything - that is the honest
     * default for a project with no CBDC connection yet (MASTER-PLAN phase 2), and it is a default rather than a
     * hidden choice because it is written down here and in the module's properties.
     *
     * <p>The mock is reached through {@link Rails#mock()} rather than by naming its class: implementations live in
     * {@code io.reductxn.rails.internal..}, which nothing outside that module may import (RULE B10), so the
     * factory in its {@code api} package is the only door.
     *
     * <p><strong>The breaker is applied here rather than in the rail.</strong> "Stop calling it for a while" is a
     * decision about this deployable's behaviour towards a rail, not about the rail's protocol - so it decorates
     * whatever rail the configuration names, and a future HTTP adapter inherits it without being written twice.
     * It shares this module's clock, which is what makes its cooldown testable without sleeping.
     */
    @Bean
    PaymentRail paymentRail(
            @Value("${reductxn.rail.name:mock}") String name,
            BreakerPolicy breakerPolicy,
            Clock clock) {

        if (!"mock".equals(name)) {
            throw new IllegalStateException(
                    "no payment rail named '" + name + "' is in this build. Configured rails: mock. A worker that "
                            + "fell back to another rail would be moving money through something nobody chose");
        }

        return Rails.withCircuitBreaker(Rails.mock(), breakerPolicy, clock);
    }

    /**
     * When the breaker in front of the rail opens, and for how long.
     *
     * <p>Configuration rather than a constant, because both numbers are properties of a deployment's relationship
     * with its rail - how flaky it is, and how long an operator expects an outage to last - and neither is a
     * statement about settlement.
     */
    @Bean
    BreakerPolicy breakerPolicy(
            @Value("${reductxn.rail.breaker.failures-before-opening:5}") int failuresBeforeOpening,
            @Value("${reductxn.rail.breaker.cooldown:PT30S}") Duration cooldown) {

        return new BreakerPolicy(failuresBeforeOpening, cooldown);
    }

    /**
     * How long a transfer waits before the rail is offered it again.
     *
     * <p>This module's policy rather than the rail's: the port says whether a refusal is retryable, and *how long
     * to wait* is a decision about this worker's behaviour. It is a bean so that the wait is visible in one place
     * and configurable per deployment, and so that a test that is not about backoff can set it to zero.
     */
    @Bean
    Backoff backoff(
            @Value("${reductxn.settlement.retry.base:PT30S}") Duration base,
            @Value("${reductxn.settlement.retry.cap:PT15M}") Duration cap) {

        return new Backoff(base, cap);
    }

    /**
     * How long a rail may go unanswered before somebody has to look at it.
     *
     * <p><strong>This is the one setting here that is a judgement rather than a rate limit.</strong> The driving
     * and retrying settings above say how hard this worker tries; this says when trying has stopped being the
     * answer and a person has to decide something the worker cannot. There is no right number - it depends on what
     * the rail settles and who is on call - so it is configuration with a stated default (two hours of silence,
     * which is about eight attempts under the default backoff), and the property file says the same thing.
     */
    @Bean
    Escalation escalation(@Value("${reductxn.settlement.escalation.after:PT2H}") Duration after) {
        return new Escalation(after);
    }

    /**
     * How long a transfer may sit at a rail without being confirmed before somebody has to look at it.
     *
     * <p>The second of the two judgements that make the stuck-transfer report (ADR 0026), and a separate setting
     * because it is a separate question: this rail has the instruction and is holding the group's money, which is a
     * different thing from a rail that has stopped answering. Its default is shorter than the escalation default on
     * purpose - an hour at the rail is long enough that a confirmation queue is not the explanation, whereas an hour
     * of silence from a rail that may still be working through a backlog is not yet news.
     */
    @Bean
    Reconciliation reconciliation(@Value("${reductxn.settlement.reconcile.after:PT1H}") Duration after) {
        return new Reconciliation(after);
    }

    /**
     * The stuck-transfer report: what no rail has decided, for the cadence's log line and the operator's command.
     *
     * <p>A read, so it takes no transaction and no clock of its own - the cut-offs come from the two policies at the
     * call site, which is what keeps each judgement in one place and makes a two-hour and a one-hour rule assertable
     * in a test that never waits.
     */
    @Bean
    ReadStuckTransfers readStuckTransfers(Runs runs) {
        return new ReadStuckTransfersService(runs);
    }

    /**
     * Recording what a person found out about a transfer no rail would decide: the way back from the report above.
     *
     * <p>The same collaborators a confirmation uses in {@code ExecuteRunService} - the ledger, the chain heads and
     * one clock - because a resolution books a movement exactly as a confirmation does. What it deliberately does not
     * take is the rail: a resolution records something somebody already knows, so it makes no call, and a bean
     * without a rail cannot make one by accident.
     */
    @Bean
    ResolveTransfer resolveTransfer(
            Runs runs,
            JournalEntries journalEntries,
            ChainHeads chainHeads,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        return new ResolveTransferService(runs, journalEntries, chainHeads, clock, transactionManager);
    }
}
