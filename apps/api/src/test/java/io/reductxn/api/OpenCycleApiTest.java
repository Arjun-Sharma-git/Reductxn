package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A group's books closed at an instant, through every layer, against a real database.
 *
 * <p><strong>This is the endpoint that makes ADR 0032 reachable for anyone but an operator.</strong> A cycle and
 * the plan over it used to be one act, so there was nothing to ask for; now the worker opens nothing on its own and
 * computes every cycle that has no run, which means *somebody else* has to open one. This is that surface.
 *
 * <p>Its subject is a row this module does not own, exactly as {@code DecisionApiTest}'s is: the assertions are
 * about the shape of what was written - the group, the instant, when it was asked for - because that row is the
 * whole of what crosses. {@code workers/settlement}'s {@code ComputeOpenedCyclesTest} writes the same four columns
 * with the same SQL and checks that the cadence turns them into a plan. Between them, a change to that table fails a
 * test rather than a deployment.
 *
 * <p><strong>What it does not assert is a refusal about the cycle, because there is not one.</strong> A cycle may be
 * opened for an instant with nothing recorded at or before it, and the worker is what refuses to plan it - which is the
 * right moment, because a commitment recorded tomorrow can carry an instant inside this cycle. A test here that expected a
 * {@code 422} would be asserting a rule that must not exist, so the absence is asserted positively instead - and **the
 * refusals it does assert are about the caller** (ADR 0049), which is the half the port's own argument had missed.
 */
@SpringBootTest
class OpenCycleApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    private static final String CALLER_PHONE = "+919812345678";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private String caller;
    private UUID callerId;


    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /**
     * A signed-in member of the group, because a member of the group is who may write to its books (ADR 0049).
     *
     * <p>The membership is written as a row: it is a precondition of the requests these tests make rather than their
     * subject, and the tests that assert the rule itself belong to the classes that own each write.
     */
    @BeforeEach
    void signInACaller() {
        caller = signedIn(CALLER_PHONE);
        callerId = user(CALLER_PHONE);
        memberOf(GROUP);
        memberOf(OTHER_GROUP);
    }

    /** A membership as a row, because {@code groups.isMember} reads exactly this. */
    private void memberOf(UUID group) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, 'Caller', 'admin', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, group, callerId);
    }

    @Test
    void aMemberOpensACycleAndTheRowIsWhereTheWorkerCanReadIt() throws Exception {
        Instant before = Instant.now();

        open(GROUP, AS_OF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.asOf").value("2026-09-30T23:59:59Z"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // Read back as the worker's own columns rather than as this module's view of them: this is the assertion the
        // other deployable depends on, so it names the columns that carry meaning.
        Map<String, Object> cycle = jdbc.queryForMap(
                "SELECT id, group_id, as_of, created_at FROM settlement_cycles WHERE group_id = ?", GROUP);

        assertThat(cycle.get("group_id")).isEqualTo(GROUP);
        assertThat(((java.sql.Timestamp) cycle.get("as_of")).toInstant())
                .as("the instant the client chose, not the instant the request arrived: it keys the cycle and it is "
                        + "what the worker resolves every rate and tax rule at")
                .isEqualTo(AS_OF);
        assertThat(((java.sql.Timestamp) cycle.get("created_at")).toInstant())
                .as("the boundary read the clock (RULE B21), and it is the instant the row carries - which is what "
                        + "a client shows as 'waiting since' when no plan exists yet")
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(cycle.get("id")).isNotNull();
    }

    @Test
    void openingTheSameCycleTwiceOpensOneCycleAndTheSecondRequestSaysSo() throws Exception {
        String firstId = openedId(open(GROUP, AS_OF).andExpect(status().isCreated()));

        open(GROUP, AS_OF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.asOf").value("2026-09-30T23:59:59Z"));

        assertThat(countIn("settlement_cycles"))
                .as("one cycle per group per instant is a unique constraint, so a retry after a timeout, a double "
                        + "click and a policy firing twice are one settlement rather than three")
                .isEqualTo(1);
    }

    @Test
    void theInstantTheCycleWasFirstOpenedForIsTheOneItKeeps() throws Exception {
        String firstId = openedId(open(GROUP, AS_OF).andExpect(status().isCreated()));

        Map<String, Object> opened = jdbc.queryForMap("SELECT created_at FROM settlement_cycles WHERE id = ?",
                UUID.fromString(firstId));

        open(GROUP, AS_OF).andExpect(status().isOk());

        Map<String, Object> again = jdbc.queryForMap("SELECT id, created_at FROM settlement_cycles WHERE group_id = ?",
                GROUP);

        assertThat(again.get("id").toString()).isEqualTo(firstId);
        assertThat(again.get("created_at"))
                .as("the second request reports the first one's instant: answering with this request's clock would "
                        + "tell a client about a row that does not exist")
                .isEqualTo(opened.get("created_at"));
    }

    @Test
    void oneInstantInTwoGroupsIsTwoCycles() throws Exception {
        open(GROUP, AS_OF).andExpect(status().isCreated());
        open(OTHER_GROUP, AS_OF).andExpect(status().isCreated());

        assertThat(countIn("settlement_cycles"))
                .as("the instant is not the key on its own: every group closes its own books")
                .isEqualTo(2);
    }

    @Test
    void aCycleMayBeOpenedForAnInstantWithNothingRecordedYet() throws Exception {
        // No obligation exists at all in this test's fixture, and the request is still accepted.
        open(GROUP, AS_OF)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.asOf").value("2026-09-30T23:59:59Z"));

        assertThat(countIn("obligations")).isZero();
        assertThat(countIn("settlement_cycles"))
                .as("the absence of a 'nothing to settle' refusal is the decision, not an omission: the group's "
                        + "commitments are this module's rows, so the check is possible here and wrong here - a "
                        + "commitment recorded tomorrow can carry an instant inside this cycle, which is the "
                        + "ordinary way to settle a backdated bill. The worker refuses an empty cycle when it "
                        + "computes it, which is the moment the question has an answer")
                .isEqualTo(1);
    }

    @Test
    void aRequestWithoutAnInstantIsRefusedWithACode() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/cycles", GROUP)
                        .header("Authorization", "Bearer " + caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        assertThat(countIn("settlement_cycles"))
                .as("a malformed request writes nothing, so a client that retries it retries one request")
                .isZero();
    }

    @Test
    void aMalformedGroupIdIsRefusedWithTheSameCode() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/cycles", "not-a-uuid")
                        .header("Authorization", "Bearer " + caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOf\":\"2026-09-30T23:59:59Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("groupId")));
    }

    @Test
    void aCycleJustOpenedHasNoPlanYetAndLeavesItOut() throws Exception {
        open(GROUP, AS_OF)
                .andExpect(status().isCreated())
                // Absent rather than null: the contract marks it nullable, and Jackson is configured not to serialize
                // nulls, so a client's `cycle.runId` is undefined until a plan exists - which is what "waiting since
                // createdAt" is drawn from.
                .andExpect(jsonPath("$.runId").doesNotExist());
    }

    @Test
    void aCycleThatAlreadyHadAPlanIsReportedWithItWhenAskedForAgain() throws Exception {
        // The `200` case, and why the open response resolves the plan rather than reporting "waiting": a member
        // asking for an instant that was settled last month should not be told nothing has happened.
        UUID cycleId = cycleOpenedAt(GROUP, AS_OF, AS_OF);
        UUID runId = planOver(cycleId);

        open(GROUP, AS_OF)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cycleId.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    /**
     * The rule this slice added: closing a group's books is a member's act (ADR 0049).
     *
     * <p>This class's own javadoc said it asserts no refusal, "because there is not one". That was true of the cycle and
     * false of the caller, which is what the port's argument had missed - it reasoned about the row being refusable or
     * not, and never about who was asking.
     */
    @Test
    void aStrangerCannotCloseAGroupsBooks() throws Exception {
        String stranger = signedIn("+919899999999");

        mvc.perform(post("/v1/groups/{groupId}/cycles", GROUP)
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        assertThat(countIn("settlement_cycles")).isZero();
    }

    @Test
    void anOpeningWithNoSessionIsRefusedWith401() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/cycles", GROUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asOf\":\"" + AS_OF + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        assertThat(countIn("settlement_cycles")).isZero();
    }

    private ResultActions open(UUID groupId, Instant asOf) throws Exception {
        return mvc.perform(post("/v1/groups/{groupId}/cycles", groupId)
                .header("Authorization", "Bearer " + caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"asOf\":\"" + asOf.toString() + "\"}"));
    }

    /** The id out of a {@code 201}, which is the only way a client learns it. */
    private static String openedId(ResultActions response) throws Exception {
        String body = response.andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private int countIn(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

        return count == null ? 0 : count;
    }

    private UUID cycleOpenedAt(UUID groupId, Instant asOf, Instant openedAt) {
        UUID cycleId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, groupId, asOf.atOffset(java.time.ZoneOffset.UTC),
                openedAt.atOffset(java.time.ZoneOffset.UTC));

        return cycleId;
    }

    /** A plan row over a cycle: this module cannot compute one and may not ask the module that can (RULE B6). */
    private UUID planOver(UUID cycleId) {
        UUID runId = UUID.randomUUID();
        var at = AS_OF.atOffset(java.time.ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'awaiting_approval', ?::timestamptz, ?::timestamptz)
                """, runId, GROUP, cycleId, at, at);

        return runId;
    }
}
