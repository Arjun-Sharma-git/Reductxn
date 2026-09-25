package io.reductxn.api.bootstrap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock the HTTP boundary reads, and the only one in this module.
 *
 * <p>Nothing below the boundary may read a clock: the planner is pure (RULE B21), and a use case that
 * called {@code Instant.now()} itself could not be tested against a past instant - which is exactly what
 * the contract's {@code asOf} parameter asks for.
 *
 * <p>UTC, because an instant is absolute. What timezone a member sees it in is a rendering decision, and
 * rendering is the client's job (ADR 0014) - a server that formatted a timestamp has already chosen a
 * locale it does not know.
 */
@Configuration
public class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
