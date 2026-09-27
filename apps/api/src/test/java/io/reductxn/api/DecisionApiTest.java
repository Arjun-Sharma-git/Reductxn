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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member answering a run, through every layer, against a real database - either answer, and who may give one.
 *
 * <p><strong>This test's real subject is a row this module does not own.</strong> {@code apps/api} writes into
 * {@code workers/settlement}'s decision rows because the member's surface is here and the run there, with no
 * dependency between the two deployables and no broker in the build (ADR 0020). So the assertions are about the
 * <em>shape</em> of what was written - the run, the member, the word, the instant - because that row is the whole of
 * what crosses. {@code workers/settlement}'s {@code RecordedDecisionsTest} writes the same rows with the same SQL and
 * checks that the run's arithmetic judges them: an approval counts toward quorum, and a refusal ends the run. Between
 * them a change to that table fails a test rather than a deployment.
 *
 * <p><strong>The answering member is the caller since ADR 0053, and five tests here exist for that rather than for
 * the row.</strong> A request with no session is a {@code 401} that writes nothing; a caller who is not in the run's
 * group cannot tell a run that exists from one that does not, because both are the one {@code 404}; a body that still
 * names a member is <em>ignored</em> rather than honoured, which is the property that makes answering for somebody
 * else impossible rather than merely unwise; and a malformed run id is a {@code 401} when nobody is signed in,
 * because who is asking is settled before the path is judged - the order {@code execute} already keeps.
 *
 * <p><strong>Both words are tested, and the second one is the interesting half.</strong> A refusal is the answer
 * whose consequence is terminal, so its crossing is asserted twice: here that the row says {@code 'reject'} and that
 * this module wrote <em>no status</em> - the run's lifecycle is not this deployable's to move - and in the worker that
 * the run ends on its next look.
 *
 * <p><strong>One test left with the field it needed.</strong> There used to be a case here for the group's clearing
 * account: the request could name it, the run's own row refused it, and the API reported the store's refusal as a
 * {@code 422} rather than a {@code 500}. The caller is the only member an answer can name now, so that case is no
 * longer reachable from a request at all - the constraint has become defence in depth against a bug rather than a
 * client's mistake, which is the improvement and the reason its test is gone rather than rewritten. The path it
 * covered is still exercised: a word the run does not know reaches the same store refusal below.
 *
 * <p>A run exists here as a fixture rather than as a settlement: written with SQL, because this module cannot run one
 * and may not call the module that does (RULE B6) - the arrangement the preview's read model already uses for the run
 * it excludes.
 */
@SpringBootTest
class DecisionApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    /** Numbers rather than ids: a session is what makes a user id real, and this fixture signs in by number. */
    private static final String ALICE = "alice";

    private static final String BORIS = "boris";

    private static final String STRANGER = "stranger";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** One sign-in per number, because a phone number identifies a user and a second row for it would collide. */
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void buildMockMvc() {
        tokens.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aMemberApprovesARunAndTheAnswerIsWhereTheRunCanReadIt() throws Exception {
        UUID runId = awaitingApprovalRun();

        answer(runId, ALICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.memberId").value(user(ALICE).toString()));

        // Read back as the run's own columns rather than as this module's view of them: this is the assertion the
        // other deployable depends on, so it names the columns that carry meaning.
        var answer = jdbc.queryForMap(
                "SELECT run_id, member_id, decision, decided_at FROM settlement_decisions WHERE member_id = ?",
                user(ALICE));

        assertThat(answer.get("run_id")).isEqualTo(runId);
        assertThat(answer.get("member_id"))
                .as("the member the row names is the caller's id. The request used to say who had answered and the "
                        + "row stored that claim; since ADR 0053 the claim does not exist, and this is the assertion "
                        + "that says so")
                .isEqualTo(user(ALICE));
        assertThat(answer.get("decision")).isEqualTo("approve");
        assertThat(answer.get("decided_at")).isNotNull();
    }

    @Test
    void anAnswerAlreadyOnTheRecordIsRefusedRatherThanOverwritten() throws Exception {
        UUID runId = awaitingApprovalRun();

        answer(runId, ALICE).andExpect(status().isOk());

        answer(runId, ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.decision_already_answered"));

        assertThat(countAnswersFor(runId))
                .as("still one answer: a decision is final, and the primary key is what makes that true rather than "
                        + "a read-then-write two requests could both pass")
                .isEqualTo(1);
    }

    @Test
    void twoMembersEachAnswerOnce() throws Exception {
        UUID runId = awaitingApprovalRun();

        answer(runId, ALICE).andExpect(status().isOk());
        answer(runId, BORIS).andExpect(status().isOk());

        assertThat(countAnswersFor(runId))
                .as("one row per member, which is what a quorum counts")
                .isEqualTo(2);
    }

    @Test
    void aMemberRefusesAndTheRunCanReadTheRefusal() throws Exception {
        UUID runId = awaitingApprovalRun();

        answerWith(runId, ALICE, "reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("reject"));

        var answer = jdbc.queryForMap(
                "SELECT decision FROM settlement_decisions WHERE member_id = ?", user(ALICE));

        assertThat(answer.get("decision"))
                .as("the second word crosses the same boundary as the first. The worker's RecordedDecisionsTest "
                        + "writes this row itself and watches the run end, so the pair of them pin the crossing from "
                        + "both sides - and neither deployable has a copy of the vocabulary the other uses")
                .isEqualTo("reject");

        assertThat(jdbc.queryForObject("SELECT status FROM settlement_runs WHERE id = ?", String.class, runId))
                .as("and this module wrote no status. The run's lifecycle is the worker's (ADR 0020), so a refusal "
                        + "recorded here leaves the run waiting until the worker's next look ends it - which is the "
                        + "worker's test to assert. If this ever fails, the API has started driving a state machine "
                        + "it does not own")
                .isEqualTo("awaiting_approval");
    }

    @Test
    void aRunThatDoesNotExistIsNotFoundRatherThanAnswered() throws Exception {
        UUID unknown = UUID.randomUUID();

        answer(unknown, ALICE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));

        assertThat(countAnswersFor(unknown)).isZero();
    }

    @Test
    void aCallerOutsideTheRunGroupCannotTellWhetherTheRunExists() throws Exception {
        UUID runId = awaitingApprovalRun();

        mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                        .header("Authorization", "Bearer " + stranger())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        assertThat(countAnswersFor(runId))
                .as("and nothing was written. The caller's membership is asked before the row, so a stranger's answer "
                        + "never reaches the store - and the code is the one every group write answers with, so a "
                        + "guessed run id cannot be tested for existence")
                .isZero();
    }

    @Test
    void anAnswerFromNobodyIsRefusedAndNothingIsWritten() throws Exception {
        UUID runId = awaitingApprovalRun();

        mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        assertThat(countAnswersFor(runId))
                .as("the refusal the `memberId` field used to express and could not enforce: an answer needs an "
                        + "author, and the session is what says who")
                .isZero();
    }

    @Test
    void aBodyThatNamesAMemberIsIgnoredRatherThanHonoured() throws Exception {
        UUID runId = awaitingApprovalRun();

        // Boris, so that the body can name somebody real: the subject is that naming him changes nothing.
        caller(BORIS);

        mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                        .header("Authorization", "Bearer " + caller(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + user(BORIS) + "\",\"decision\":\"reject\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(user(ALICE).toString()));

        var answer = jdbc.queryForMap(
                "SELECT member_id, decision FROM settlement_decisions WHERE run_id = ?", runId);

        assertThat(answer.get("member_id"))
                .as("the row names the caller, and the id in the body changed nothing. That is what makes answering "
                        + "for somebody else impossible rather than merely unwise - and it is why the field was "
                        + "removed from the type instead of being left for the boundary to overwrite")
                .isEqualTo(user(ALICE));
        assertThat(answer.get("decision")).isEqualTo("reject");
    }

    @Test
    void anAnswerThatIsNotInTheRunsVocabularyIsRefusedByItsOwnRows() throws Exception {
        UUID runId = awaitingApprovalRun();

        answerWith(runId, ALICE, "maybe")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.decision_not_acceptable"));

        assertThat(countAnswersFor(runId))
                .as("the list of answers lives in the migration's check constraint rather than in a Java enum, so "
                        + "this module has nothing to keep in step with it - and a word the run does not know is a "
                        + "coded 422 rather than a 500")
                .isZero();
    }

    @Test
    void aMalformedRunIdIsRefusedWithACodeWhenTheCallerIsKnown() throws Exception {
        mvc.perform(post("/v1/runs/{runId}/decisions", "not-a-uuid")
                        .header("Authorization", "Bearer " + caller(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    @Test
    void aMalformedRunIdWithoutASessionIsTheRefusalTheSessionGets() throws Exception {
        // The order is asserted rather than assumed: who is asking is settled before the path is judged, so a
        // request with no session is told nothing about the id it sent. `execute` keeps the same order, and two
        // endpoints refusing in different orders would be a difference a client could see.
        mvc.perform(post("/v1/runs/{runId}/decisions", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));
    }

    /**
     * An answer with an empty body.
     *
     * <p>Absent {@code decision} means approve, and since ADR 0053 there is nothing else a body can carry: the
     * member's id was the field that made one necessary, and the session supplies the author now.
     */
    private ResultActions answer(UUID runId, String phone) throws Exception {
        return answerWith(runId, phone, null);
    }

    /**
     * An answer that says which one it is.
     *
     * <p>Separate from {@link #answer} rather than a parameter on it, because the request that omits the field is a
     * case worth testing on its own: absent means approve, which is what the operation meant before refusals were
     * expressible.
     */
    private ResultActions answerWith(UUID runId, String phone, String decision) throws Exception {
        String body = decision == null ? "{}" : "{\"decision\":\"" + decision + "\"}";

        return mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                .header("Authorization", "Bearer " + caller(phone))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A signed-in member of the run's group, by number - signed in once per test.
     *
     * <p>The membership belongs to the fixture rather than to each test, because since ADR 0053 it is the condition
     * that lets an answer be written at all: a test that had to remember to join its caller first would be testing
     * its own setup in every method.
     */
    private String caller(String phone) {
        return tokens.computeIfAbsent(phone, number -> {
            String token = signedIn(number);
            memberOf(GROUP, user(number));

            return token;
        });
    }

    /** A signed-in person with no membership in the run's group, which is the whole of what makes them a stranger. */
    private String stranger() {
        return tokens.computeIfAbsent(STRANGER, this::signedIn);
    }

    /**
     * A membership as the table holds it, for a caller who has to be in the group before their answer is writable.
     *
     * <p>Duplicated from the invite tests' and the members tests' fixtures, deliberately: this is a row rather than a
     * rule, and a third copy of a row reads better than a shared helper with three parameters.
     */
    private void memberOf(UUID group, UUID person) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, 'Member', 'member', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, group, person);
    }

    private int countAnswersFor(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM settlement_decisions WHERE run_id = ?", Integer.class, runId);
    }

    /**
     * A run waiting on the group, as the worker leaves it: a cycle and the run, with no partitions or transfers.
     *
     * <p>Deliberately minimal. An approval is about a run's identity and its answers, and a fixture that built a
     * whole plan would be this test pretending to be a settlement - which is the other module's test to write.
     */
    private UUID awaitingApprovalRun() {
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, GROUP, AS_OF.atOffset(ZoneOffset.UTC), AS_OF.atOffset(ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'awaiting_approval', ?::timestamptz, ?::timestamptz)
                """, runId, GROUP, cycleId, AS_OF.atOffset(ZoneOffset.UTC), AS_OF.atOffset(ZoneOffset.UTC));

        return runId;
    }
}
