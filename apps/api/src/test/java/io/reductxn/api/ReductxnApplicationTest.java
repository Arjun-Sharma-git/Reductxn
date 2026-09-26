package io.reductxn.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skeleton's most basic promise: the application context starts.
 *
 * <p>It used to need a profile, and the reason is worth keeping: the stores were development doubles behind
 * {@code dev} while the use case was not, so the default profile had no bean to inject and the context
 * refused to build. {@code MissingStoreFailsClosedTest} asserted that refusal in both directions, because
 * the mechanism - a stub that cannot reach an environment - was the whole point of the design.
 *
 * <p>The doubles are gone, so the test went with them: there is no stub left to keep out of a deployment.
 * What replaced it is stricter rather than looser. The context now needs a real database with the schema
 * migrated, and if Flyway finds no migrations the guard in {@code platform/persistence} refuses to start
 * instead of leaving an empty schema behind a successful boot.
 */
@SpringBootTest
class ReductxnApplicationTest extends DatabaseTest {

    @Autowired
    private Clock clock;

    @Test
    void theContextStartsAgainstAMigratedSchema() {
        assertThat(clock.getZone())
                .as("the boundary's clock, which is the only one in the module")
                .isEqualTo(Clock.systemUTC().getZone());

        Integer tables = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_name IN ('obligations', 'tax_rules')
                """, Integer.class);

        assertThat(tables)
                .as("both owning modules' migrations ran: obligations from apps/api, tax_rules from "
                        + "libs/netting. Flyway reads them from the classpath of whoever owns them, which "
                        + "is the arrangement RULE B17 asks for and the one most likely to break silently")
                .isEqualTo(2);
    }
}
