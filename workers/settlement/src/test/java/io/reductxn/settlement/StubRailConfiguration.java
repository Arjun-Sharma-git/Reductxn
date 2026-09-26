package io.reductxn.settlement;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The rail the tests drive, as one bean for whichever test classes ask for it.
 *
 * <p>Extracted from {@code ExecuteRunTest} when a second class needed to execute a run: the configuration is
 * part of a Spring context's key, so two classes that declare the same one share the context - and the same
 * {@link StubRail} instance. That is what makes "the rail was asked once" a question a test can answer, and it
 * is also why {@link StubRail#reset()} has to be called before each test rather than assumed.
 *
 * <p>{@code @Primary} rather than replacing the module's own rail: the real bean stays wired, which is part of
 * what the context proves - a deployment gets a working rail by default - and this one takes precedence for the
 * calls the tests make.
 */
@TestConfiguration
public class StubRailConfiguration {

    @Bean
    @Primary
    StubRail stubRail() {
        return new StubRail();
    }
}
