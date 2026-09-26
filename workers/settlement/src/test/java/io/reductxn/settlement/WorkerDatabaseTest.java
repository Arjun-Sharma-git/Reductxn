package io.reductxn.settlement;

import io.reductxn.fx.api.FxQuote;
import io.reductxn.fx.api.FxRate;
import io.reductxn.kernel.api.Result;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.settlement.port.in.ComputeRun;
import io.reductxn.settlement.port.in.ComputedRun;
import io.reductxn.settlement.port.in.DecideOnRun;
import io.reductxn.settlement.port.in.ExecuteRun;
import io.reductxn.settlement.port.in.FundingRequest;
import io.reductxn.settlement.port.in.RecordFxFunding;
import io.reductxn.settlement.port.in.RunDecision;
import io.reductxn.settlement.port.in.RunRequest;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Postgres for the worker's tests, with the fixtures a run needs.
 *
 * <p>Testcontainers rather than a substitute, because what is being verified is the SQL: the migrations, the
 * per-currency keys, the {@code timestamptz} comparisons and the unique rail reference. The image is the one the
 * compose stack uses.
 *
 * <p><strong>The obligations are seeded with SQL rather than recorded through the API.</strong> The worker may
 * not depend on {@code apps/api} (RULE B6), and a fixture that called it would be the first step towards exactly
 * that. The rows written here are the rows the API writes, because they are in the API's table: this is a test
 * standing in for a group's recorded commitments, not a second way of recording them.
 *
 * <p>One container for the whole JVM, one schema, cleared between tests. Distinct group ids would work too;
 * clearing is simpler and impossible to forget.
 */
abstract class WorkerDatabaseTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** The cycle's cut-off. Fixed, so nothing here depends on the clock. */
    protected static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    /** The instants a locked quote carries, and the instant an obligation is recorded at. */
    protected static final Instant RATE_QUOTED_AT = Instant.parse("2026-09-24T10:00:00Z");
    protected static final Instant RATE_EXPIRES_AT = Instant.parse("2026-09-24T10:01:00Z");
    protected static final Instant RATE_LOCKED_AT = Instant.parse("2026-09-24T10:00:30Z");
    protected static final Instant OBLIGATION_RECORDED_AT = Instant.parse("2026-09-20T10:00:00Z");

    protected static final Currency INR = Currency.of("INR", 2);
    protected static final Currency JPY = Currency.of("JPY", 0);

    protected static final UUID GROUP = UUID.fromString("11111111-1111-4111-8111-111111111111");
    protected static final UUID ALICE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    protected static final UUID BORIS = UUID.fromString("33333333-3333-4333-8333-333333333333");
    protected static final UUID CAROL = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @DynamicPropertySource
    static void pointAtTheContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Flyway uses the same URL here on purpose. In a deployment it points at a different port because DDL
        // cannot go through PgBouncer (see platform/persistence); a container has no pooler, and inventing one
        // would test the proxy rather than the migration. The credentials have to be repeated because setting
        // spring.flyway.url stops Flyway inheriting the DataSource's.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // Required with no default in the application, so every test has to say which jurisdiction it is in.
        registry.add("reductxn.tax.jurisdiction", () -> "IN");

        // The worker's own migrations, plus the API's. The two deployables share one schema and each jar brings
        // only its own migrations (RULE B17), so `V101__obligations.sql` is not on this module's classpath - and
        // may not be, because the worker may not depend on apps/api (RULE B6). Pointing Flyway at the file
        // keeps that boundary and still gives the test the schema a deployment has, since in a deployment the
        // API applies its own migration to the same database.
        Path apiMigrations = Path.of("..", "..", "apps", "api", "src", "main", "resources", "db", "migration")
                .toAbsolutePath()
                .normalize();

        registry.add("spring.flyway.locations",
                () -> "classpath:db/migration,filesystem:" + apiMigrations);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * The inbound ports, not the service classes.
     *
     * <p>Deliberate: a port is what a caller would have, so a test driving a service directly would stop being
     * evidence about the interface anyone uses - and the class could be replaced without touching a test.
     */
    @Autowired
    protected ComputeRun computeRun;

    @Autowired
    protected DecideOnRun decideOnRun;

    @Autowired
    protected RecordFxFunding recordFxFunding;

    @Autowired
    protected ExecuteRun executeRun;

    @BeforeEach
    void resetSchema() {
        // The plan's tables first, children before parents. The foreign keys are within this module, which is
        // why they exist at all (RULE B17 allows them inside one owner), and they have to be respected here.
        jdbc.execute("DELETE FROM settlement_transfers");
        jdbc.execute("DELETE FROM net_positions");
        jdbc.execute("DELETE FROM settlement_partitions");
        jdbc.execute("DELETE FROM settlement_run_obligations");
        jdbc.execute("DELETE FROM settlement_decisions");
        jdbc.execute("DELETE FROM settlement_fx_fundings");
        jdbc.execute("DELETE FROM settlement_runs");
        jdbc.execute("DELETE FROM settlement_cycles");

        // The ledger, which this module now writes: a settlement entry and its postings. Emptied like every
        // other table, so a test that counts entries is counting its own.
        jdbc.execute("DELETE FROM postings");
        jdbc.execute("DELETE FROM journal_entries");

        jdbc.execute("DELETE FROM obligations");
        jdbc.execute("DELETE FROM fx_quotes");
        jdbc.execute("DELETE FROM fx_rates");
        jdbc.execute("DELETE FROM tax_rules");

        // A flat charge per transfer, in the currencies these tests use. The same amount in each: it is a
        // per-transfer charge rather than a value, so the same number in a coarser currency is a heavier tax -
        // which is the point of assessing it per currency, and why the figures below differ between them.
        taxRule("INR", "{\"perTransferMinor\": 50}");
        taxRule("JPY", "{\"perTransferMinor\": 50}");
    }

    protected void taxRule(String currency, String paramsJson) {
        jdbc.update("""
                INSERT INTO tax_rules (id, jurisdiction, currency_code, kind, params_json,
                                       effective_from, source_ref)
                VALUES (?, 'IN', ?, 'flat', ?::jsonb, TIMESTAMPTZ '2020-01-01 00:00:00+00', 'test fixture')
                """, UUID.randomUUID(), currency, paramsJson);
    }

    /** An ordinary obligation: the creditor is paid in the debt currency. */
    protected UUID obligation(UUID debtor, UUID creditor, long amountMinor, String currency, int exponent) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor, currency_code,
                                         currency_exponent, memo, idempotency_key, quote_id, incurred_at,
                                         voided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, TIMESTAMPTZ '2026-09-20 10:00:00+00', NULL)
                """, id, GROUP, debtor, creditor, amountMinor, currency, exponent, "key-" + id);

        return id;
    }

    /**
     * An obligation voided before the cycle's cut-off, which a plan must not include.
     *
     * <p>Voided rather than deleted, because that is what the API does: an obligation is never deleted, and a
     * fixture that deleted one would test a state the product cannot be in.
     */
    protected UUID voidedObligation(UUID debtor, UUID creditor, long amountMinor, String currency, int exponent) {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor, currency_code,
                                         currency_exponent, memo, idempotency_key, quote_id, incurred_at,
                                         voided_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, TIMESTAMPTZ '2026-09-20 10:00:00+00',
                        TIMESTAMPTZ '2026-09-21 10:00:00+00')
                """, id, GROUP, debtor, creditor, amountMinor, currency, exponent, "key-" + id);

        return id;
    }

    /**
     * A cross-currency obligation: the creditor is paid in another currency, at a locked rate.
     *
     * <p>The quote and the obligation are written together because that is how the API writes them - one
     * transaction, quote first - and a fixture that could write an obligation without its quote would let a test
     * set up a state the record path cannot produce.
     *
     * <p><strong>The quote is built in Java and inserted from its own fields, and it is returned.</strong> It
     * used to be written as literal SQL with the converted amount and residual typed into the statement, which
     * made the fixture a second implementation of the conversion - one that could disagree with {@code libs/fx}
     * without anything noticing. Building it through {@code FxQuote.lock} means the fixture writes what the
     * library computed, and returning it lets a test that needs the ledger side book the same pair
     * ({@code Bookings.crossCurrency} takes the quote).
     *
     * <p>The rate is ADR 0013's, the other way round: 1 INR is 1.78878 JPY, so INR 125.00 is 223.5975 JPY and
     * converts to 224, with the residual stored as the fraction it is: -0.4025 of a yen, negative because
     * rounding rounded up.
     */
    protected FxQuote crossCurrencyObligation(UUID debtor, UUID creditor, long baseMinor, long convertedMinor) {
        UUID obligationId = UUID.randomUUID();

        FxRate rate = FxRate.ofMid(INR, JPY, 178_878L, 5, RATE_QUOTED_AT, RATE_EXPIRES_AT, "test fixture");
        FxQuote quote = FxQuote.lock(UUID.randomUUID(), Money.of(baseMinor, INR), rate, RATE_LOCKED_AT, false);

        if (!quote.convertedAmount().equals(Money.of(convertedMinor, JPY))) {
            // The fixture takes the converted amount as an argument because the tests assert it, so a
            // disagreement between the argument and what libs/fx computes has to be visible here rather than
            // producing an obligation whose two stored halves contradict each other.
            throw new IllegalArgumentException(
                    "the fixture was told " + convertedMinor + " JPY but the rate converts " + baseMinor
                            + " INR to " + quote.convertedAmount() + ". One of the two is wrong, and both are stored");
        }

        jdbc.update("""
                INSERT INTO fx_quotes (id, base_code, base_exponent, base_amount_minor, quote_code,
                                       quote_exponent, converted_amount_minor, residual_scaled, residual_scale,
                                       rate_scaled, rate_scale, mid_rate_scaled, spread_bps, rate_source,
                                       rate_quoted_at, rate_expires_at, locked_at, rail_performed_fx)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, quote.id(), quote.baseAmount().currency().code(),
                quote.baseAmount().currency().minorUnitExponent(), quote.baseAmount().amountMinor(),
                quote.convertedAmount().currency().code(), quote.convertedAmount().currency().minorUnitExponent(),
                quote.convertedAmount().amountMinor(), quote.residualScaled(), quote.residualScale(),
                quote.rate().rateScaled(), quote.rate().rateScale(), quote.rate().midRateScaled(),
                quote.rate().spreadBps(), quote.rate().source(),
                RATE_QUOTED_AT.atOffset(ZoneOffset.UTC), RATE_EXPIRES_AT.atOffset(ZoneOffset.UTC),
                RATE_LOCKED_AT.atOffset(ZoneOffset.UTC), quote.railPerformedFx());

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor, currency_code,
                                         currency_exponent, memo, idempotency_key, quote_id, incurred_at,
                                         voided_at)
                VALUES (?, ?, ?, ?, ?, 'INR', 2, NULL, ?, ?, TIMESTAMPTZ '2026-09-20 10:00:00+00', NULL)
                """, obligationId, GROUP, debtor, creditor, baseMinor, "key-" + obligationId, quote.id());

        return quote;
    }

    /** How many rows a table holds, for the tests that assert nothing was written. */
    protected int countIn(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    /** Computes a run for the fixture group, insisting that it worked. */
    protected ComputedRun computed(RunRequest request) {
        return ok(computeRun.compute(request));
    }

    /** One member's answer, insisting that it was accepted. */
    protected ComputedRun decided(UUID runId, UUID memberId, RunDecision.Decision decision) {
        return ok(decideOnRun.decide(new RunDecision(runId, memberId, decision)));
    }

    /** Every member of the run approves it, which is more than S1 needs and never less. */
    protected void approveEveryone(ComputedRun run) {
        for (UUID member : run.quorum().members()) {
            decided(run.run().id(), member, RunDecision.Decision.APPROVE);
        }
    }

    /** Every currency the run needs is funded, in one arrival each. */
    protected void fundEveryRequirement(ComputedRun run) {
        int index = 0;

        for (Money requirement : run.funding().required()) {
            ok(recordFxFunding.fund(new FundingRequest(
                    run.run().id(), requirement.currency(), requirement, "funding-" + (index++))));
        }
    }

    /** The value of a call that had to succeed. */
    protected static ComputedRun ok(Result<ComputedRun> result) {
        assertThat(result.isOk()).as("expected success, got %s", result).isTrue();
        return ((Result.Ok<ComputedRun>) result).value();
    }

    /**
     * The error of a refusal that had to happen.
     *
     * <p>The code rather than the message, because the message is for a human and the code is the contract - and a
     * test that pinned the prose would fail every time it was improved.
     */
    protected static io.reductxn.kernel.api.DomainError refusalOf(Result<?> result) {
        assertThat(result.isFailure()).as("expected a refusal, got %s", result).isTrue();
        return ((Result.Failure<?>) result).error();
    }

    /** The code of a refusal that had to happen. */
    protected static String refusalCode(Result<?> result) {
        return refusalOf(result).code();
    }
}
