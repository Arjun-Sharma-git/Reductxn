package io.reductxn.platform.persistence;

import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * Runs migrations, or refuses to start.
 *
 * <p><strong>Flyway with nothing to migrate is not a no-op, it is a silently empty database.</strong>
 * It logs a line nobody reads, the application starts, and the first query fails against a table that was
 * never created - at which point the cause looks like a permissions problem or a wrong URL, and the
 * actual answer is that the migrations were never on the classpath. That happens easily here: migrations
 * live beside their owning module (RULE B17), so the set is assembled from several jars, and a module
 * whose resources directory is not where Flyway looks contributes nothing while looking perfectly fine.
 *
 * <p>So the count is checked before migrating, and zero stops the process with the locations in the
 * message. This is the same shape as the other guards in this repository: a green start that proves
 * nothing is worse than a red one that says why.
 *
 * <p>There is no flag to allow an empty schema. A deployable with no tables is a deployable that cannot
 * answer a query, so "allow empty" would be a setting whose only effect is to postpone the same failure
 * to a harder-to-read place. When a module genuinely has no tables yet it does not need this wiring yet.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationsConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationsConfiguration.class);

    @Bean
    FlywayMigrationStrategy migrateOrRefuseToStart() {
        return flyway -> {
            MigrationInfo[] discovered = flyway.info().all();

            if (discovered.length == 0) {
                throw new IllegalStateException(
                        "Flyway found no migrations, so starting would leave an empty schema. Migrations "
                                + "live beside their owning module (RULE B17), and Flyway read from "
                                + locations(flyway)
                                + " - check that each owning module has a "
                                + "src/main/resources/db/migration directory and that its jar is on the "
                                + "classpath. Refusing to start rather than failing later at the first "
                                + "query.");
            }

            LOG.info("applying {} migration(s) from {}", discovered.length, locations(flyway));
            flyway.migrate();
        };
    }

    /**
     * The configured locations, as text.
     *
     * <p>{@code getLocations()} returns an array, so concatenating it directly puts a JVM object identity in
     * the message - and this message exists to say <em>where</em> Flyway looked, which is the one thing a
     * reader cannot work out from anywhere else. The integration test caught it by asserting the message
     * names the location it was pointed at.
     */
    private static String locations(org.flywaydb.core.Flyway flyway) {
        return Arrays.toString(flyway.getConfiguration().getLocations());
    }
}
