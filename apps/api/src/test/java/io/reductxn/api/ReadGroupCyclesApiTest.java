package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The cycles a group has closed, read back through every layer, against a real database.
 *
 * <p><strong>This is the read that makes an opened cycle observable.</strong> Without it, a member who asked for a
 * settlement (ADR 0033) and a member whose cycle the worker refused to plan - nothing recorded at or before the
 * instant - saw the same thing: nothing. ADR 0030 added the group's runs for the same reason at the other end of the
 * flow, and this is the earlier half: a cycle has to be findable to be waited on.
 *
 * <p><strong>What it asserts is the {@code runId}, which is a fact rather than a status.</strong> A cycle with no
 * plan is not an error and not a lifecycle state - it is the ordinary state of a cycle for up to a minute after it
 * was asked for, and permanently for one the worker refuses - so the read reports the plan's absence and the client
 * shows "waiting since". Recording the refusal would be a column and a judgement this module does not make, and
 * pretending the two situations were one status would be worse than admitting it: the list is ordered by when
 * cycles were *asked for*, so a cycle that waits for days is an anomaly a reader can see rather than a state the
 * server invented.
 *
 * <p><strong>The caller is a member, since ADR 0054</strong>, and this is the third read the slice closed that used to
 * serve a bare uuid. It is also the one where the rule costs the least and buys the most: a cycle is the group's own
 * claim about when its books closed, so a page of them says what a group decided and when - which is exactly the kind
 * of thing an id should not be enough to read.
 */
@SpringBootTest
class ReadGroupCyclesApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    private static final String MEMBER_PHONE = "+919812345682";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The caller: a member of both groups, so "these cycles are one group's" is a question a reader can ask. */
    private String member;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        member = signedIn(MEMBER_PHONE, new UUID(0L, 82L));
        memberRow(GROUP, user(MEMBER_PHONE), "Reader");
        memberRow(OTHER_GROUP, user(MEMBER_PHONE), "Reader");
    }

    /** The caller's request: every group read needs a session and a membership since ADR 0054. */
    private MockHttpServletRequestBuilder read(String path, Object groupId) {
        return get(path, groupId).header("Authorization", "Bearer " + member);
    }

    @Test
    void aGroupWithNoCyclesAnswersAnEmptyListRatherThanAnError() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCycleIsListedWithTheInstantItClosedAndNoPlanYet() throws Exception {
        open(GROUP, AS_OF);

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$[0].asOf").value("2026-09-30T23:59:59Z"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                // The absence is the assertion: a cycle asked for a moment ago has no plan, and a client says
                // "waiting since createdAt" rather than guessing at a state.
                .andExpect(jsonPath("$[0].runId").doesNotExist());
    }

    @Test
    void aCycleThatHasBeenComputedCarriesItsPlan() throws Exception {
        UUID cycleId = cycleOpenedAt(GROUP, AS_OF, AS_OF.plusSeconds(60));
        UUID runId = planOver(cycleId);

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(cycleId.toString()))
                .andExpect(jsonPath("$[0].runId").value(runId.toString()));
    }

    @Test
    void thePageIsNewestFirstByWhenTheCycleWasAskedFor() throws Exception {
        // The interesting case: the older *instant* was asked for later, so `created_at` order and `as_of` order
        // disagree. A member who has just asked for last quarter's settlement wants it at the top, which is what the
        // index this read was designed around orders by.
        Instant lastQuarter = Instant.parse("2026-06-30T23:59:59Z");
        open(GROUP, AS_OF);
        open(GROUP, lastQuarter);

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].asOf").value("2026-06-30T23:59:59Z"))
                .andExpect(jsonPath("$[1].asOf").value("2026-09-30T23:59:59Z"));
    }

    @Test
    void oneGroupsCyclesAreNotAnothers() throws Exception {
        open(GROUP, AS_OF);
        open(OTHER_GROUP, AS_OF);

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].groupId").value(GROUP.toString()));
    }

    @Test
    void theLimitIsBoundedByTheContractRatherThanByTheRequest() throws Exception {
        open(GROUP, AS_OF);
        open(GROUP, AS_OF.plusSeconds(3600));

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // A zero or negative limit is a caller's arithmetic rather than a reason to answer with an error: the read
        // clamps it, so the worst a client can do is ask for the smallest page.
        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP).param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aMalformedGroupIdIsRefusedWithACode() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/cycles", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("groupId")));
    }

    /**
     * The cycles are the group's, and since ADR 0054 that is a condition rather than an assumption.
     *
     * <p>A request with no session answers `401`; a session that is not a member of the group answers the same `404` a
     * group that does not exist answers. The second is the one that changed: this endpoint served a group's settlement
     * history to anybody who held its id.
     */
    @Test
    void theCyclesAreTheGroupMembersOnly() throws Exception {
        open(GROUP, AS_OF);

        mvc.perform(get("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        String stranger = signedIn("+919812345683", new UUID(0L, 83L));

        mvc.perform(get("/v1/groups/{groupId}/cycles", GROUP).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(get("/v1/groups/{groupId}/cycles", UUID.randomUUID())
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));
    }

    @Test
    void theListNeverReportsACycleTwiceForOnePlan() throws Exception {
        UUID cycleId = cycleOpenedAt(GROUP, AS_OF, AS_OF);
        planOver(cycleId);

        // The join is what could multiply a cycle, and the constraint that stops it is the worker's
        // `one_run_per_cycle`. Asserted rather than assumed: a second row per cycle is the kind of bug a screen shows
        // as a duplicate and nobody can explain.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM settlement_runs WHERE cycle_id = ?", Integer.class, cycleId)).isEqualTo(1);

        mvc.perform(read("/v1/groups/{groupId}/cycles", GROUP))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * A cycle written as a row.
     *
     * <p>This used to go through the endpoint, so the fixture was the write path rather than a copy of it. That stopped
     * being the right call in ADR 0049, when opening a cycle became a member's act: a read test whose fixture had to
     * join the group first would count its own fixture in every assertion, and the counts are what this class asserts.
     * The write's rules are {@code OpenCycleApiTest}'s subject, against the same database; what this read serves is the
     * same rows whoever wrote them.
     *
     * <p>{@code created_at} is the clock's now here, and only where a test does not care: the ordering test writes its
     * instants explicitly, through {@link #cycleOpenedAt}, because that is the one that does.
     */
    private void open(UUID groupId, Instant asOf) {
        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, now())
                """, UUID.randomUUID(), groupId, toOffset(asOf));
    }

    private UUID cycleOpenedAt(UUID groupId, Instant asOf, Instant openedAt) {
        UUID cycleId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, groupId, toOffset(asOf), toOffset(openedAt));

        return cycleId;
    }

    /** A plan row, written the way {@code ReadRunApiTest} writes one: this module cannot compute, and may not ask. */
    private UUID planOver(UUID cycleId) {
        UUID runId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'awaiting_approval', ?::timestamptz, ?::timestamptz)
                """, runId, GROUP, cycleId, toOffset(AS_OF), toOffset(AS_OF));

        return runId;
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
