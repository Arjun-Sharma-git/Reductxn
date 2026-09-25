package io.reductxn.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stub must not be able to reach an environment, and this is the mechanism that stops it.
 *
 * <p>The doubles in {@code adapter/dev} are behind the {@code dev} profile while the use case that needs
 * them is not. So in the default profile there is no {@code OpenObligations} bean, the context cannot be
 * built, and the process refuses to start. That is the difference between a labelled stub and a stub
 * somebody deploys: the label is a comment, the missing bean is a mechanism.
 *
 * <p>Asserting the failure would look odd in isolation - "this test passes when the application cannot
 * start" - which is why the second test asserts the other direction. Together they pin the behaviour
 * rather than one side of it.
 */
class MissingStoreFailsClosedTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ReductxnApplication.class);

    @Test
    void withoutTheDevProfileTheContextRefusesToStart() {
        runner.run(context -> assertThat(context)
                .as("no OpenObligations bean exists outside the dev profile, so nothing can run on a "
                        + "development double by accident")
                .hasFailed());
    }

    @Test
    void withTheDevProfileTheDoublesSatisfyThePorts() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
