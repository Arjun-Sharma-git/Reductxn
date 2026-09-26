package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * A real Postgres for tests that need the schema to exist.
 *
 * <p>Testcontainers rather than an in-memory substitute, because what is being verified is the SQL: the
 * migration, the partial index, the {@code jsonb} column and the {@code timestamptz} comparisons. H2 would
 * accept a dialect-adjacent version of all of that and prove the opposite of the thing under test, which is
 * why the image is the same one the local compose stack uses.
 *
 * <p>One container for the whole test JVM - starting Postgres per class is the slowest thing in this
 * module by an order of magnitude - so tests share a schema and {@link #resetSchema()} clears it between
 * them. Distinct group ids would work too; truncating is both simpler and impossible to forget.
 */
abstract class DatabaseTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void pointAtTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Flyway uses the same URL here on purpose. The application points it at a different port in
        // production because DDL cannot go through PgBouncer (see platform/persistence); a container has no
        // pooler, and inventing one would test the proxy rather than the migration.
        //
        // The credentials have to be repeated: setting spring.flyway.url makes Flyway use its own connection
        // rather than the DataSource, so it stops inheriting the username and password with it.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // Required with no default in the application, so every test has to say which jurisdiction it is in.
        registry.add("reductxn.tax.jurisdiction", () -> "IN");
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetSchema() {
        jdbc.execute("DELETE FROM obligations");
        jdbc.execute("DELETE FROM tax_rules");

        // A flat charge per transfer, in both currencies these tests use. Deliberately the same amount in
        // each: it is a per-transfer charge, not a value, so the same number in a coarser currency is a
        // heavier tax - which is the point of assessing it per currency.
        taxRule("IN", "INR", "flat", "{\"perTransferMinor\": 50}");
        taxRule("IN", "JPY", "flat", "{\"perTransferMinor\": 50}");
    }

    protected void taxRule(String jurisdiction, String currency, String kind, String paramsJson) {
        jdbc.update("""
                INSERT INTO tax_rules (id, jurisdiction, currency_code, kind, params_json,
                                       effective_from, source_ref)
                VALUES (?, ?, ?, ?, ?::jsonb, TIMESTAMPTZ '2020-01-01 00:00:00+00', 'test fixture')
                """, UUID.randomUUID(), jurisdiction, currency, kind, paramsJson);
    }

    protected void obligate(
            UUID group, UUID debtor, UUID creditor, long amountMinor, String currency, int exponent) {

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor,
                                         currency_code, currency_exponent, incurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-09-01 00:00:00+00')
                """, UUID.randomUUID(), group, debtor, creditor, amountMinor, currency, exponent);
    }

    protected void voidObligationOf(UUID debtorId, String voidedAt) {
        jdbc.update("UPDATE obligations SET voided_at = TIMESTAMPTZ " + "'" + voidedAt + "'"
                + " WHERE debtor_id = ?", debtorId);
    }
}
