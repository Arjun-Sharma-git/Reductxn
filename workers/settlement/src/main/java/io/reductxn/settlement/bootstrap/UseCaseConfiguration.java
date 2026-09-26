package io.reductxn.settlement.bootstrap;

import io.reductxn.planning.api.SettlementPlanner;
import io.reductxn.planning.api.SettlementPlans;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
