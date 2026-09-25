package io.reductxn.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skeleton's most basic promise: the application context starts.
 *
 * <p>Normally a formality. Here it is load-bearing and needs a profile, because the stores this module
 * needs are deliberately absent outside {@code dev} - so "it boots" is not true by default, it is true
 * only when something is actually wired. {@code MissingStoreFailsClosedTest} asserts the other half.
 */
@SpringBootTest
@ActiveProfiles("dev")
class ReductxnApplicationTest {

    @Autowired
    private Clock clock;

    @Test
    void theContextStartsWithTheCoreBeans() {
        assertThat(clock.getZone()).isEqualTo(Clock.systemUTC().getZone());
        assertThat(ReductxnApplication.class).isNotNull();
    }
}
