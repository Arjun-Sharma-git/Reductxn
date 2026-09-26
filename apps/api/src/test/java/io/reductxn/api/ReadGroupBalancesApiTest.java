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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract's balances endpoint, through every layer, against a real database.
 *
 * <p>This is the read the contract promised as {@code NetPosition[]} and no handler served - one of the seventeen
 * gaps {@code ContractCoverageTest} was written to record (ADRs 0038, 0039). The tests are about the two decisions
 * the read makes rather than about the plumbing: that a balance is the *plan's* position, so it cannot disagree with
 * the preview, and that "open" means here what it means everywhere else in this build.
 *
 * <p>Every assertion is on the wire format the contract defines, so a change to the schema fails here rather than in
 * a client.
 */
@SpringBootTest
class ReadGroupBalancesApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);
    private static final UUID CAROL = new UUID(0L, 12L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Built from the framework rather than annotated: Spring Boot 4 no longer ships {@code @AutoConfigureMockMvc}. */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void reportsOnePositionPerMemberWithTheDirectionTheSignMeans() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, CAROL, BORIS, 4_000L, "INR", 2);

        balances(GROUP)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Three members, two obligations: ALICE pays 10,000, CAROL pays 4,000, BORIS receives 14,000 - and
                // the direction is a word rather than a sign, which is what a screen renders and a screen reader
                // reads.
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].direction", containsInAnyOrder("pay", "pay", "receive")))
                .andExpect(jsonPath("$[*].net.currency", containsInAnyOrder("INR", "INR", "INR")));
    }

    /**
     * The claim the read's whole design rests on: it is the preview's own computation (ADR 0039).
     *
     * <p>Asserted as one body containing the other, which needs a word of defence. Both responses are written by the
     * same serializer from the same record type, so the positions array in the preview's body is byte-identical to
     * this read's body - which makes the containment check an exact comparison of the numbers, their order and their
     * currency, without a second JSON library: {@code jackson-databind} is not on this module's compile classpath (the
     * web layer only needs its annotations), and adding a dependency to a test that needs to compare two strings
     * would be the tail wagging the dog.
     *
     * <p>The {@code startsWith} and {@code hasSize} assertions are what stop that from being a test that passes when
     * both bodies are empty: an empty JSON array is contained in almost any document.
     */
    @Test
    void agreesWithThePreviewOverTheSameOpenObligations() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, BORIS, ALICE, 4_000L, "INR", 2);
        obligate(GROUP, CAROL, ALICE, 2_500L, "INR", 2);

        String preview = mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        balances(GROUP).andExpect(jsonPath("$", hasSize(3)));

        String read = balances(GROUP).andReturn().getResponse().getContentAsString();

        assertThat(read)
                .as("the balances read and the netting preview answer with the same positions, because they are the "
                        + "same computation - the group's open obligations netted by the same planner")
                .startsWith("[{");
        assertThat(preview).contains(read);
    }

    @Test
    void aMemberHoldingTwoCurrenciesAppearsOncePerCurrency() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, ALICE, BORIS, 10_000L, "JPY", 0);

        balances(GROUP)
                .andExpect(status().isOk())
                // Two members in two currencies: four entries, and nothing added up across the two. A single total
                // would need a reporting currency and a rate nobody agreed (ADR 0012).
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].net.currency", containsInAnyOrder("INR", "INR", "JPY", "JPY")));
    }

    @Test
    void aCommitmentASettledRunDischargedIsNotABalance() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, CAROL, BORIS, 4_000L, "INR", 2);

        settledByADoneRun(jdbc.queryForObject(
                "SELECT id FROM obligations WHERE debtor_id = ?", UUID.class, ALICE));

        balances(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].net.amountMinor", containsInAnyOrder(4_000, -4_000)));
    }

    @Test
    void aGroupWithNothingOpenHasNoBalancesRatherThanAnError() throws Exception {
        balances(new UUID(0L, 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void frameworkErrorsAreProblemsToo() throws Exception {
        // A group id that is not a UUID never reaches the controller, and the contract says so: `400` with a problem
        // body, so a client can branch on it rather than on an empty response.
        mvc.perform(get("/v1/groups/{groupId}/balances", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private ResultActions balances(UUID groupId) throws Exception {
        return mvc.perform(get("/v1/groups/{groupId}/balances", groupId));
    }

    /**
     * A run that reached {@code done} and settled this commitment, as the worker leaves it.
     *
     * <p>Written with SQL because the API may not run a settlement (RULE B6) - the same fixture the preview's tests
     * use, and the reason the two reads can be asserted to agree on what "open" means.
     */
    private void settledByADoneRun(UUID obligationId) {
        UUID runId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-20T10:00:00Z");

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, GROUP, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'done', ?::timestamptz, ?::timestamptz)
                """, runId, GROUP, cycleId, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));

        jdbc.update("INSERT INTO settlement_run_obligations (run_id, obligation_id) VALUES (?, ?)",
                runId, obligationId);
    }
}
