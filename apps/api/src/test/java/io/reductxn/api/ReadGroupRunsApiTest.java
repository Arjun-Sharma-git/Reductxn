package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The runs a group has to decide about: the endpoint without which the approval path was unreachable.
 *
 * <p><strong>What this test is really pinning is the order.</strong> A list a member reads to choose what to answer
 * is only usable if the thing that needs an answer is where it was last time. The order is the index's
 * ({@code group_id, created_at DESC}) plus the run's id as the tie-break, and the fixture is written so that a
 * reader returning "whatever the database gave" would fail here rather than in production - the same argument
 * {@code V501}'s comment makes about transfer order, and the same technique its test uses.
 *
 * <p>The other half is what the list refuses to be: it does not filter by status, and it does not invent a refusal
 * for a group it has never heard of. Both are asserted, because both are decisions rather than omissions - one keeps
 * a page stable without a second ordering guarantee, and the other is the honest answer while groups are not tables
 * in this build.
 *
 * <p>Rows arrive as SQL because this module cannot run a settlement and may not call the module that does (RULE B6).
 * The worker's own tests pin what it writes; these pin what this reader says about it.
 */
@SpringBootTest
class ReadGroupRunsApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 8L);
    private static final UUID OTHER_GROUP = new UUID(0L, 9L);

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void theGroupsRunsReadNewestFirstWhateverOrderTheyWereWrittenIn() throws Exception {
        // Written oldest first and asserted newest first: the order has to come from the query rather than from the
        // order the rows happen to have, which is the failure this catches.
        UUID oldest = run(GROUP, "done", Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:00:00Z"));
        UUID middle = run(GROUP, "awaiting_approval", Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"));
        UUID newest = run(GROUP, "computing", Instant.parse("2026-09-28T10:00:00Z"), Instant.parse("2026-09-28T10:00:00Z"));

        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].id", contains(
                        newest.toString(), middle.toString(), oldest.toString())))
                // A row carries the figures a member needs to decide whether to open it, through the same mapper
                // the read endpoint uses - so the two endpoints cannot describe one run differently.
                .andExpect(jsonPath("$[1].status").value("awaiting_approval"))
                .andExpect(jsonPath("$[1].partitions", hasSize(1)))
                .andExpect(jsonPath("$[1].partitions[0].net.amountMinor").value(300_000))
                .andExpect(jsonPath("$[1].partitions[0].net.currency").value("INR"))
                // `transfers` are served by their own endpoint and are absent from a list row, as the schema says:
                // a page of twenty runs does not need every rail instruction in it.
                .andExpect(jsonPath("$[0].transfers").doesNotExist());
    }

    @Test
    void anotherGroupsRunsAreNotInThisGroupsPage() throws Exception {
        run(GROUP, "awaiting_approval", AS_OF, AS_OF);
        run(OTHER_GROUP, "awaiting_approval", AS_OF, AS_OF);

        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].groupId").value(GROUP.toString()));
    }

    @Test
    void aTieOnCreatedAtIsBrokenByTheRunItselfRatherThanByChance() throws Exception {
        // Two runs created in the same instant, from two cycles a second apart - because the schema refuses two
        // cycles of one group at one instant (`settlement_cycles_are_unique_per_group_and_instant`), so a tie on the
        // run's own created_at is reachable only across cycles. Which is exactly the case a page has to survive: a
        // worker that computed two cycles in the same second, or two rows written by one batch.
        UUID first = run(GROUP, "awaiting_approval", AS_OF, AS_OF);
        UUID second = run(GROUP, "awaiting_approval", AS_OF.plusSeconds(1), AS_OF);

        // A page whose order depended on which row the database returned first would show a member a different list
        // on every read.
        String lowest = first.compareTo(second) < 0 ? first.toString() : second.toString();
        String highest = first.compareTo(second) < 0 ? second.toString() : first.toString();

        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(highest, lowest)));
    }

    @Test
    void theLimitIsTheContractsDefaultAndTheApisCeiling() throws Exception {
        for (int i = 0; i < 3; i++) {
            run(GROUP, "awaiting_approval", AS_OF.plusSeconds(i), AS_OF.plusSeconds(i));
        }

        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // A client may ask for more than the ceiling; it gets the ceiling rather than an error, because the request
        // is not wrong - it is asking for more than one page holds.
        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP).param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // And zero is raised to one rather than answering an empty page, which would be the response claiming
        // "this group has no runs" - something it does not know.
        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP).param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void aGroupWithNoRunsAndAGroupNobodyHasHeardOfBothReadEmpty() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/v1/groups/{groupId}/runs", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anIdThatIsNotAnIdIsRefusedRatherThanLookedUp() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/runs", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /**
     * A cycle and a run at chosen instants, with one partition so a row has figures rather than only an id.
     *
     * <p>Two instants rather than one, because they are two different columns with two different constraints: the
     * cycle's instant is unique per group (the schema says a group opens one cycle at a time), while the run's
     * {@code created_at} is the column a page is ordered by. A fixture that wrote both from one argument could not
     * produce the tie the ordering test needs, and could not tell a correct order from an arbitrary one.
     */
    private UUID run(UUID groupId, String status, Instant cycleAsOf, Instant runCreatedAt) {
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OffsetDateTime cycleAt = cycleAsOf.atOffset(ZoneOffset.UTC);
        OffsetDateTime createdAt = runCreatedAt.atOffset(ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, groupId, cycleAt, cycleAt);

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, runId, groupId, cycleId, status, createdAt, createdAt);

        jdbc.update("""
                INSERT INTO settlement_partitions
                    (run_id, currency_code, currency_exponent, gross_minor, net_minor, tax_minor, tax_saved_minor)
                VALUES (?, 'INR', 2, 500000, 300000, 400, 1200)
                """, runId);

        return runId;
    }
}
