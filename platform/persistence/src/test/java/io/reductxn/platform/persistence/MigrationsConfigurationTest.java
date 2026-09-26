package io.reductxn.platform.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two halves of the migration guard, against a real Postgres.
 *
 * <p>Both halves matter and neither is sufficient. A strategy that only refused would never migrate; one that
 * only migrated would not catch the case this exists for. The failure it guards against is quiet by
 * construction - Flyway logs a line, the application starts, and the first query fails against a table that
 * was never created - so "it does not throw" is not evidence that it works.
 *
 * <p>Each test uses its own schema. Flyway's history table is per schema, so an instance pointed at an empty
 * location would still see the other test's applied migrations in a shared schema, and the refusal would
 * never fire. A test whose result depends on which of its neighbours ran first is a test that will pass in
 * isolation and mislead in CI.
 */
class MigrationsConfigurationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    private final MigrationsConfiguration configuration = new MigrationsConfiguration();

    @Test
    void refusesToStartWhenNoMigrationIsFound() {
        Flyway flyway = flyway("guard_check", "classpath:db/migration/this-location-is-empty");

        assertThatThrownBy(() -> configuration.migrateOrRefuseToStart().migrate(flyway))
                .as("an empty migration set is a successful start behind an empty schema. The first query "
                        + "then fails with something about permissions or a URL, and the cause - nothing was "
                        + "on the classpath - is nowhere in it")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no migrations")
                .hasMessageContaining("this-location-is-empty");
    }

    @Test
    void migratesWhenThereIsSomethingToMigrate() {
        Flyway flyway = flyway("with_migrations", "classpath:db/migration");

        configuration.migrateOrRefuseToStart().migrate(flyway);

        assertThat(flyway.info().applied())
                .as("refusing is not the whole job: the strategy delegates to migrate")
                .isNotEmpty();

        // By script rather than by position: the first applied entry is Flyway's own schema-creation marker,
        // which has no version at all, and indexing into it asserted something about Flyway's bookkeeping
        // rather than about the migration.
        assertThat(flyway.info().applied())
                .extracting(info -> info.getScript())
                .contains("V1__fixture.sql");
    }

    private static Flyway flyway(String schema, String location) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(location)
                .load();
    }
}
