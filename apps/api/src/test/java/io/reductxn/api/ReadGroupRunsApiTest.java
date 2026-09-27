package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
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
 * <p>The other half is what the list still refuses to be: it does not filter by status, and it does not answer a group
 * the caller is not in. Both are asserted, because both are decisions rather than omissions - one keeps a page stable
 * without a second ordering guarantee, and the other is the refusal every read of a group's books answers with since
 * ADR 0054. This doc used to say the second was "the honest answer while groups are not tables in this build", which
 * was true when it was written and false from ADR 0043 - and it was the version of honesty that served a group's
 * history to anybody who could paste its id.
 *
 * <p>Rows arrive as SQL because this module cannot run a settlement and may not call the module that does (RULE B6).
 * The worker's own tests pin what it writes; these pin what this reader says about it.
 */
@SpringBootTest
class ReadGroupRunsApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 8L);
    private static final UUID OTHER_GROUP = new UUID(0L, 9L);

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    private static final String MEMBER_PHONE = "+919812345680";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The caller: a member of both groups, so "this page is one group's" is a reader's question rather than a 404. */
    private String member;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        // A fixed id, because the fixture's runs are rows about a group and the caller has to be in it.
        member = signedIn(MEMBER_PHONE, new UUID(0L, 80L));
        memberRow(GROUP, user(MEMBER_PHONE), "Reader");
        memberRow(OTHER_GROUP, user(MEMBER_PHONE), "Reader");
    }

    /** The caller's request: every group read needs a session and a membership since ADR 0054. */
    private MockHttpServletRequestBuilder read(String path, Object groupId) {
        return get(path, groupId).header("Authorization", "Bearer " + member);
    }

    @Test
    void theGroupsRunsReadNewestFirstWhateverOrderTheyWereWrittenIn() throws Exception {
        // Written oldest first and asserted newest first: the order has to come from the query rather than from the
        // order the rows happen to have, which is the failure this catches.
        UUID oldest = run(GROUP, "done", Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:00:00Z"));
        UUID middle = run(GROUP, "awaiting_approval", Instant.parse("2026-09-15T10:00:00Z"), Instant.parse("2026-09-15T10:00:00Z"));
        UUID newest = run(GROUP, "computing", Instant.parse("2026-09-28T10:00:00Z"), Instant.parse("2026-09-28T10:00:00Z"));

        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP))
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

        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP))
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
        //
        // The ids are given rather than random, and that is a correction this test needed. The first version drew two
        // random UUIDs and predicted the page's order with `UUID.compareTo` - but Java's comparison is *signed* over
        // the two halves while PostgreSQL's `uuid` comparison is unsigned byte order, so the prediction was right
        // about half the time and the test passed or failed by luck. It failed on the first cold clone and nowhere
        // else. Small ids compare the same way under both rules, so the expectation is now a fact rather than a
        // guess - and what the test is about is the tie-break existing at all, not which of two random ids is
        // greater.
        UUID first = run(GROUP, "awaiting_approval", AS_OF, AS_OF, new UUID(0L, 101L));
        UUID second = run(GROUP, "awaiting_approval", AS_OF.plusSeconds(1), AS_OF, new UUID(0L, 102L));

        // `id DESC`, as the database orders it: the higher id first.
        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains(second.toString(), first.toString())));
    }

    @Test
    void theLimitIsTheContractsDefaultAndTheApisCeiling() throws Exception {
        for (int i = 0; i < 3; i++) {
            run(GROUP, "awaiting_approval", AS_OF.plusSeconds(i), AS_OF.plusSeconds(i));
        }

        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // A client may ask for more than the ceiling; it gets the ceiling rather than an error, because the request
        // is not wrong - it is asking for more than one page holds.
        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP).param("limit", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)));

        // And zero is raised to one rather than answering an empty page, which would be the response claiming
        // "this group has no runs" - something it does not know.
        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP).param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    /**
     * A group with no runs answers an empty list; a group the caller is not in answers the same `404` a group that
     * does not exist does.
     *
     * <p><strong>The second half changed with ADR 0054 and the first did not.</strong> "A group with no runs" is still
     * an empty page, because that is a fact about rows rather than about permission - but a group id that is not the
     * caller's is now the refusal every other read of a group's books answers with, and it carries the code a group
     * which does not exist carries, so the two cannot be told apart by asking.
     */
    @Test
    void aGroupWithNoRunsReadsEmptyAndAGroupThatIsNotTheCallersIsRefused() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        String stranger = signedIn("+919812345681", new UUID(0L, 81L));

        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(get("/v1/groups/{groupId}/runs", UUID.randomUUID())
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        // And with no session at all, which is the first answer rather than the last: who is asking comes before what
        // was asked.
        mvc.perform(get("/v1/groups/{groupId}/runs", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));
    }

    @Test
    void anIdThatIsNotAnIdIsRefusedRatherThanLookedUp() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/runs", "not-a-uuid"))
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
        return run(groupId, status, cycleAsOf, runCreatedAt, UUID.randomUUID());
    }

    private UUID run(UUID groupId, String status, Instant cycleAsOf, Instant runCreatedAt, UUID runId) {
        UUID cycleId = UUID.randomUUID();
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
