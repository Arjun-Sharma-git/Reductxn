package io.reductxn.settlement;

import io.reductxn.rails.api.BreakerPolicy;
import io.reductxn.rails.api.PaymentRail;
import io.reductxn.rails.api.Rails;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * The application's rail, with a breaker in front of it, built the way the deployment builds it.
 *
 * <p>Shared by the two classes that are about a rail that does not answer, because in both of them the subject is
 * the composition `UseCaseConfiguration` performs: {@code Rails.withCircuitBreaker} around whatever rail the
 * configuration named - here a stub that can be told to fail, there the mock which cannot.
 *
 * <p><strong>Why the tests need their own rail at all.</strong> {@code StubRailConfiguration} makes the stub
 * {@code @Primary}, which means the application's guarded bean is not what a use case gets - so a test that wants
 * to see the breaker has to build it around the stub. That leaves the factory call in {@code UseCaseConfiguration}
 * covered only by these tests doing the same thing, which is worth saying out loud: what is verified is the
 * composition, and the deployment's own line is a one-liner whose effect no test can observe because the mock
 * cannot fail.
 */
@TestConfiguration
public class GuardedStubConfiguration {

    @Bean
    StubRail stubRail() {
        return new StubRail();
    }

    @Bean
    @Primary
    PaymentRail guardedRail(
            StubRail stub,
            MovingClockConfiguration.MovingClock clock,
            @Value("${reductxn.rail.breaker.failures-before-opening}") int failuresBeforeOpening,
            @Value("${reductxn.rail.breaker.cooldown}") Duration cooldown) {

        return Rails.withCircuitBreaker(stub, new BreakerPolicy(failuresBeforeOpening, cooldown), clock);
    }
}
