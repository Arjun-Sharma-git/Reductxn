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
 * The contract's preview endpoint, through every layer, against a real database.
 *
 * <p>These tests have now run in two stages, and the difference is the point. They began over in-memory
 * doubles, when no store existed: they proved the mapping and the layering, and could not prove that
 * anything was persisted. Rows are now written with SQL into a container and read back through jOOQ, so a
 * broken migration, a misnamed column and a timezone mistake all fail here rather than in an environment.
 *
 * <p>The assertions are on the wire format the contract defines, which is why they survived both stages
 * unchanged - the shape a client sees was never the part that was provisional.
 */
@SpringBootTest
class NettingPreviewApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);
    private static final UUID CAROL = new UUID(0L, 12L);

    /** The flat charge per transfer the fixture seeds, in minor units of whichever currency is assessed. */
    private static final long FLAT_TAX_MINOR = 50L;

    private static final String AS_OF = "2026-09-25T10:00:00Z";

    /** A group with nothing open that the caller *is* in: the only kind whose empty plan is readable (ADR 0054). */
    private static final UUID EMPTY_GROUP = new UUID(0L, 999L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The caller: one of the members the fixture's obligations name, because a preview is the group's (ADR 0054). */
    private String alice;

    /**
     * Built from the framework rather than annotated, because Spring Boot 4 no longer ships
     * {@code @AutoConfigureMockMvc} - it is not in any of the module's artifacts. This goes through the
     * same {@code WebApplicationContext} the application uses, so the controller, the resolver and the
     * problem-details advice are all the real ones.
     */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        // Alice reads and Alice owes, which is the ordinary case: the person asking about a plan is in it.
        alice = signedIn("+919812345688", ALICE);
        memberRow(GROUP, ALICE, "Alice");
    }

    @Test
    void aCommitmentASettledRunDischargedIsNotPreviewed() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, CAROL, BORIS, 4_000L, "INR", 2);

        UUID settled = jdbc.queryForObject(
                "SELECT id FROM obligations WHERE debtor_id = ?", UUID.class, ALICE);

        settledByADoneRun(settled);

        preview(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(1)))
                .andExpect(jsonPath("$.bestCase.transfers[0].fromUserId").value(CAROL.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor")
                        .value(4_000L))
                .andExpect(jsonPath("$.bestCase.positions", hasSize(2)));
    }

    /**
     * A run that reached {@code done} and settled this commitment, as the worker leaves it.
     *
     * <p>Written with SQL because the API cannot run a settlement - and may not call the module that does (RULE
     * B6). This is a fixture standing in for another deployable's writes, exactly as the worker's own tests write
     * obligation rows: the point is the state, and the state is what a deployment's database holds.
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

    @Test
    void previewsASingleCurrencyGroupInTheContractShape() throws Exception {
        // Two opposing obligations net to one transfer of 6,000. Pairwise settlement would have been two
        // transfers, so with a flat per-transfer tax the bill falls from 100 to 50.
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, BORIS, ALICE, 4_000L, "INR", 2);

        preview(GROUP)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.grossTax.amountMinor").value(2 * FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.netTax.amountMinor").value(FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.taxSaved.amountMinor").value(FLAT_TAX_MINOR))
                .andExpect(jsonPath("$.partitions", hasSize(1)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[0].grossTransferCount").value(2))
                .andExpect(jsonPath("$.partitions[0].netTransferCount").value(1))
                .andExpect(jsonPath("$.partitions[0].algorithm").value("greedy"))
                .andExpect(jsonPath("$.bestCase.positions", hasSize(2)))
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(1)))
                .andExpect(jsonPath("$.bestCase.transfers[0].fromUserId").value(ALICE.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toUserId").value(BORIS.toString()))
                .andExpect(jsonPath("$.bestCase.transfers[0].toAccountType").value("member"))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(6_000L))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[0].status").value("pending"))
                // Absent rather than zero: collapsing currencies needs a reporting rate (ADR 0012 point 7),
                // and a zero here would read as "this plan is free".
                .andExpect(jsonPath("$.reportingTotals").doesNotExist())
                .andExpect(jsonPath("$.sameCurrencyAlternative").doesNotExist());
    }

    @Test
    void aMulticurrencyGroupReportsPartitionsRatherThanChoosingACurrency() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, ALICE, BORIS, 10_000L, "JPY", 0);

        preview(GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(2)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[1].currency").value("JPY"))
                // The single-currency fields are absent rather than collapsed into one of the two
                // currencies, which is the choice ADR 0012 refuses to make silently.
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.taxSaved").doesNotExist())
                // The detail view still carries every leg, each with its own currency, so no base
                // currency has to be chosen to read it.
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(2)))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.currency").value("INR"))
                .andExpect(jsonPath("$.bestCase.transfers[1].amount.currency").value("JPY"));
    }

    @Test
    void aGroupWithNothingOpenPreviewsAnEmptyPlanRatherThanFailing() throws Exception {
        memberRow(EMPTY_GROUP, ALICE, "Alice");

        preview(EMPTY_GROUP)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partitions", hasSize(0)))
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(0)))
                .andExpect(jsonPath("$.bestCase.positions", hasSize(0)))
                // No plan means no optimality claim to make, so the flag is absent rather than true: an
                // empty set does not get to be provably optimal.
                .andExpect(jsonPath("$.provablyOptimal").doesNotExist());
    }

    @Test
    void frameworkErrorsAreProblemsToo() throws Exception {
        // A group id that is not a UUID is refused by this controller rather than by Spring, since ADR 0054 - the
        // caller is resolved first, so the session is what makes the malformed id answerable at all.
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", "not-a-uuid")
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    /**
     * A preview is the group's plan, and since ADR 0054 that is a condition.
     *
     * <p>This endpoint and the balances read were the two the client's home screen reached directly: pasting a group id
     * navigated to {@code /groups/{id}/netting}, so a stranger holding an id saw the plan the group was about to
     * execute. The refusal is the same `404` a group that does not exist gives, and the box that used to take an id
     * takes an invitation code now.
     */
    @Test
    void thePreviewIsTheGroupMembersOnly() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP).param("asOf", AS_OF))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        String stranger = signedIn("+919812345689", new UUID(0L, 13L));

        mvc.perform(get("/v1/groups/{groupId}/netting/preview", GROUP)
                        .param("asOf", AS_OF)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));
    }

    @Test
    void anObligationVoidedAfterTheInstantWasStillOpenAtIt() throws Exception {
        // Voided on 20 September. At 10:00 on 15 September it was open, so a preview of that instant must
        // still see it: the filter is `voided_at > asOf`, not `voided_at IS NULL`. A preview that dropped it
        // would understate a past cycle's cost by exactly what has been voided since, which is the kind of
        // error that never looks like an error.
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        voidObligationOf(ALICE, "2026-09-20 00:00:00+00");

        preview(GROUP, "2026-09-15T10:00:00Z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(1)))
                .andExpect(jsonPath("$.bestCase.transfers[0].amount.amountMinor").value(10_000L));

        // And at the later instant it is gone, which is what makes the first half a claim about time rather
        // than about voiding being ignored.
        preview(GROUP, AS_OF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bestCase.transfers", hasSize(0)))
                .andExpect(jsonPath("$.partitions", hasSize(0)));
    }

    private ResultActions preview(UUID groupId) throws Exception {
        return preview(groupId, AS_OF);
    }

    private ResultActions preview(UUID groupId, String asOf) throws Exception {
        return mvc.perform(get("/v1/groups/{groupId}/netting/preview", groupId)
                .param("asOf", asOf)
                .header("Authorization", "Bearer " + alice));
    }
}
