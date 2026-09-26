package io.reductxn.settlement.bootstrap;

import io.reductxn.planning.api.SettlementPlanner;
import io.reductxn.planning.api.SettlementPlans;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.rails.api.Rails;
import io.reductxn.settlement.application.DriveRunsService;
import io.reductxn.settlement.port.in.DriveRuns;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.out.Runs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

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
     * The rail this worker submits settlement transfers to.
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
     */
    @Bean
    PaymentRail paymentRail(@Value("${reductxn.rail.name:mock}") String name) {
        if (!"mock".equals(name)) {
            throw new IllegalStateException(
                    "no payment rail named '" + name + "' is in this build. Configured rails: mock. A worker that "
                            + "fell back to another rail would be moving money through something nobody chose");
        }

        return Rails.mock();
    }
}
