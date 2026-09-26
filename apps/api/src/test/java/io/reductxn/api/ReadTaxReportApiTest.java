package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract's tax report endpoint, through every layer, against a real database.
 *
 * <p>This endpoint had a path, a summary and two parameters in the contract and **no response schema at all** - so
 * these tests are about the shape as much as the arithmetic. What they pin down is what the operation's description
 * decides: that the report is about *plans* rather than about money that moved, that a refused plan's tax is released
 * rather than counted, that a period is matched against the cycle's instant rather than the run's creation, and that
 * there is no figure anywhere that adds one currency to another.
 */
@SpringBootTest
class ReadTaxReportApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Built from the framework rather than annotated: Spring Boot 4 no longer ships {@code @AutoConfigureMockMvc}. */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void sumsAPlansTaxPerCurrencyAndNoFurther() throws Exception {
        plan(GROUP, "2026-09-20T10:00:00Z", "awaiting_approval",
                partition("INR", 2, 50L, 550L),
                partition("JPY", 0, 50L, 150L));

        report(GROUP)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // One line per currency and no third line: ADR 0012 refuses a cross-currency total, and the schema
                // has no field that could hold one.
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                // Gross is the tax the plan assesses plus what netting saved it, recovered rather than stored - the
                // same arithmetic the preview's mapper does for one partition.
                .andExpect(jsonPath("$[0].grossTax.amountMinor").value(600))
                .andExpect(jsonPath("$[0].netTax.amountMinor").value(50))
                .andExpect(jsonPath("$[0].taxSaved.amountMinor").value(550))
                .andExpect(jsonPath("$[0].runCount").value(1))
                .andExpect(jsonPath("$[1].currency").value("JPY"))
                .andExpect(jsonPath("$[1].taxSaved.amountMinor").value(150));
    }

    @Test
    void addsUpSeveralPlansOfOneCurrencyAndCountsThem() throws Exception {
        plan(GROUP, "2026-09-10T10:00:00Z", "awaiting_approval", partition("INR", 2, 50L, 550L));
        plan(GROUP, "2026-09-20T10:00:00Z", "awaiting_fx_funding", partition("INR", 2, 50L, 800L));

        report(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].netTax.amountMinor").value(100))
                .andExpect(jsonPath("$[0].taxSaved.amountMinor").value(1_350))
                .andExpect(jsonPath("$[0].grossTax.amountMinor").value(1_450))
                // How many plans the line is made of: what lets a reader tell one cycle's tax from a quarter's.
                .andExpect(jsonPath("$[0].runCount").value(2));
    }

    /**
     * A refused plan is not a cost; a plan still waiting is.
     *
     * <p>{@code rejected} is the group saying no and {@code failed} is a plan that was attempted and did not complete -
     * the same reading the obligations list uses when it says such a run *releases* what it held, so its tax is
     * released too. A plan in {@code awaiting_approval} has been computed and not yet answered, and its tax is exactly
     * what the group is being asked about, which is why it counts.
     */
    @Test
    void countsAPlanThatIsWaitingButNotOneTheGroupRefusedOrThatFailed() throws Exception {
        plan(GROUP, "2026-09-10T10:00:00Z", "awaiting_approval", partition("INR", 2, 50L, 550L));
        plan(GROUP, "2026-09-11T10:00:00Z", "rejected", partition("INR", 2, 5_000L, 9_000L));
        plan(GROUP, "2026-09-12T10:00:00Z", "failed", partition("INR", 2, 7_000L, 7_000L));

        report(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].netTax.amountMinor").value(50))
                .andExpect(jsonPath("$[0].runCount").value(1));
    }

    @Test
    void aPlanStillBeingComputedContributesNothingBecauseItHasNoFiguresYet() throws Exception {
        // A run in `pending` or `computing` has no partition rows - the plan is not built - so it is absent from the
        // report because there is nothing to report rather than because of a filter. Asserted rather than assumed: a
        // report that counted runs instead of plans would answer one run and zero tax.
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        insertCycle(GROUP, cycleId, "2026-09-10T10:00:00Z");
        insertRun(GROUP, runId, cycleId, "computing", "2026-09-10T10:00:00Z");

        report(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    /**
     * The period is the cycle's instant, and its end day is included in full.
     *
     * <p>Two cycles: one that closed late on the last day of the period and one that closed at midnight the next
     * morning. The first counts and the second does not - and a report whose end bound was the end day's own midnight
     * would drop everything settled on the day the reader asked about, which is the day they most likely care about.
     */
    @Test
    void thePeriodIsTheCycleInstantAndTheEndDayIsIncluded() throws Exception {
        plan(GROUP, "2026-09-30T23:59:00Z", "awaiting_approval", partition("INR", 2, 50L, 550L));
        plan(GROUP, "2026-10-01T00:00:00Z", "awaiting_approval", partition("INR", 2, 7_000L, 7_000L));

        report(GROUP, "periodStart=2026-09-01", "periodEnd=2026-09-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].netTax.amountMinor").value(50))
                .andExpect(jsonPath("$[0].runCount").value(1));

        // And the next day's cycle is the only thing a period starting on it finds, which is what makes the first half
        // a claim about the boundary rather than about a filter that never matched.
        report(GROUP, "periodStart=2026-10-01")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netTax.amountMinor").value(7_000));
    }

    @Test
    void omittingThePeriodReportsEverythingTheGroupHasPlanned() throws Exception {
        plan(GROUP, "2026-07-10T10:00:00Z", "awaiting_approval", partition("INR", 2, 50L, 550L));
        plan(GROUP, "2026-09-20T10:00:00Z", "awaiting_approval", partition("INR", 2, 50L, 550L));

        report(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runCount").value(2));
    }

    @Test
    void aGroupWithNoPlansHasAnEmptyReportRatherThanAnError() throws Exception {
        report(new UUID(0L, 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void frameworkErrorsAreProblemsToo() throws Exception {
        // `groupId` is required and is a UUID: either mistake is answered before any method here runs, with a problem
        // body the contract's response list promises.
        mvc.perform(get("/v1/tax/reports"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(get("/v1/tax/reports").param("groupId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private ResultActions report(UUID groupId, String... params) throws Exception {
        var request = get("/v1/tax/reports").param("groupId", groupId.toString());

        for (String param : params) {
            String[] pair = param.split("=", 2);
            request = request.param(pair[0], pair[1]);
        }

        return mvc.perform(request);
    }

    /**
     * One computed plan: a cycle, a run in the given state, and a partition per currency.
     *
     * <p>Written with SQL because the API cannot run a settlement (RULE B6) - the same reason the preview's and runs'
     * tests write their fixtures this way.
     */
    private void plan(UUID group, String asOf, String status, Partition... currencies) {
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        insertCycle(group, cycleId, asOf);
        insertRun(group, runId, cycleId, status, asOf);

        for (Partition partition : currencies) {
            jdbc.update("""
                    INSERT INTO settlement_partitions (run_id, currency_code, currency_exponent,
                                                      gross_minor, net_minor, tax_minor, tax_saved_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    // The two volume columns hold values nothing asserts on: `gross_minor` is the plan's transferred
                    // volume, which is a different figure from the gross *tax* the report recovers from the assessed
                    // tax and the saving - and a fixture that could set both would be a fixture that could write a
                    // plan the report contradicts.
                    runId, partition.currency(), (short) partition.exponent(),
                    1_000_000L, 250_000L, partition.taxMinor(), partition.taxSavedMinor());
        }
    }

    /** A plan's tax line as the worker stores it: what was assessed, and what netting saved. */
    private static Partition partition(String currency, int exponent, long taxMinor, long taxSavedMinor) {
        return new Partition(currency, exponent, taxMinor, taxSavedMinor);
    }

    private record Partition(String currency, int exponent, long taxMinor, long taxSavedMinor) {
    }

    private void insertCycle(UUID group, UUID cycleId, String asOf) {
        Instant at = Instant.parse(asOf);

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, group, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));
    }

    private void insertRun(UUID group, UUID runId, UUID cycleId, String status, String asOf) {
        Instant at = Instant.parse(asOf);

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, runId, group, cycleId, status, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));
    }
}
