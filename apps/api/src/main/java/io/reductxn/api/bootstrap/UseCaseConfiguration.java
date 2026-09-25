package io.reductxn.api.bootstrap;

import io.reductxn.planning.api.SettlementPlanner;
import io.reductxn.planning.api.SettlementPlans;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring only. No logic lives in this package - a class here that makes a decision is a class in
 * the wrong package.
 *
 * <p>These are explicit beans rather than components found by scanning, so the module graph is readable
 * in one file: what the API depends on, and which ports something has to satisfy.
 */
@Configuration
public class UseCaseConfiguration {

    /**
     * The planner, as a bean rather than a static call at each use site.
     *
     * <p>There is one, and there is deliberately no multicurrency alternative: netting across currencies
     * by valuing everything in a base currency destroys per-currency zero-sum, so the plan would not be
     * settlable in the currencies members actually hold (ADR 0012).
     */
    @Bean
    SettlementPlanner settlementPlanner() {
        return SettlementPlans.perCurrency();
    }
}
