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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member answering a run, through every layer, against a real database - either answer.
 *
 * <p><strong>This test's real subject is a row this module does not own.</strong> {@code apps/api} writes into
 * {@code workers/settlement}'s decision rows because the member's surface is here and the run there, with no
 * dependency between the two deployables and no broker in the build (ADR 0020). So the assertions are about the
 * <em>shape</em> of what was written - the run, the member, the word, the instant - because that row is the whole of
 * what crosses. {@code workers/settlement}'s {@code RecordedDecisionsTest} writes the same rows with the same SQL and
 * checks that the run's arithmetic judges them: an approval counts toward quorum, and a refusal ends the run. Between
 * them a change to that table fails a test rather than a deployment.
 *
 * <p><strong>Both words are tested, and the second one is the interesting half.</strong> A refusal is the answer
 * whose consequence is terminal, so its crossing is asserted twice: here that the row says {@code 'reject'} and that
 * this module wrote <em>no status</em> - the run's lifecycle is not this deployable's to move - and in the worker
 * that the run ends on its next look.
 *
 * <p>A run exists here as a fixture rather than as a settlement: written with SQL, because this module cannot run
 * one and may not call the module that does (RULE B6) - the arrangement the preview's read model already uses for
 * the run it excludes.
 */
@SpringBootTest
class DecisionApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID ALICE = new UUID(0L, 70L);
    private static final UUID BORIS = new UUID(0L, 71L);

    /** The clearing account the run's decisions cannot come from, as {@code V502}'s constraint spells it. */
    private static final UUID FX_CLEARING = UUID.fromString("00000000-0000-0000-0000-00000000f0c1");

    private static final Instant AS_OF = Instant.parse("2026-09-30T23:59:59Z");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aMemberApprovesARunAndTheAnswerIsWhereTheRunCanReadIt() throws Exception {
        UUID runId = awaitingApprovalRun();
        Instant before = Instant.now();

        answer(runId, ALICE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.memberId").value(ALICE.toString()));

        // Read back as the run's own columns rather than as this module's view of them: this is the assertion the
        // other deployable depends on, so it names the columns that carry meaning.
        var answer = jdbc.queryForMap(
                "SELECT run_id, member_id, decision, decided_at FROM settlement_decisions WHERE member_id = ?",
                ALICE);

        assertThat(answer.get("run_id")).isEqualTo(runId);
        assertThat(answer.get("member_id")).isEqualTo(ALICE);
        assertThat(answer.get("decision"))
                .as("the run's vocabulary, written by an adapter that owns no words: the migration's check "
                        + "constraint and the run's quorum are the authority, and the worker's test pins the same "
                        + "word from the reading side")
                .isEqualTo("approve");
        assertThat(((java.sql.Timestamp) answer.get("decided_at")).toInstant())
                .as("the boundary read the clock (RULE B21), and it is the instant the row carries")
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    void theSameMemberAnsweringTwiceIsRefusedRatherThanOverwritten() throws Exception {
        UUID runId = awaitingApprovalRun();

        answer(runId, ALICE).andExpect(status().isOk());

        answer(runId, ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.decision_already_answered"));

        assertThat(countAnswersFor(runId))
                .as("still one answer: a decision is final, and the primary key is what makes that true rather "
                        + "than a read-then-write two requests could both pass")
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
    void aRunThatDoesNotExistIsNotFoundRatherThanAnswered() throws Exception {
        UUID unknown = UUID.randomUUID();

        answer(unknown, ALICE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));

        assertThat(countAnswersFor(unknown)).isZero();
    }

    @Test
    void anAnswerTheRunCannotHoldIsRefusedByItsOwnRows() throws Exception {
        UUID runId = awaitingApprovalRun();

        answer(runId, FX_CLEARING)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.decision_not_acceptable"));

        assertThat(countAnswersFor(runId))
                .as("the clearing account is a participant in the run's arithmetic and not a member of it, so the "
                        + "run's own rows refuse its answer. This module reports that rather than deciding it - the "
                        + "membership rules are the run's - and the client gets a code rather than a 500")
                .isZero();
    }

    @Test
    void aMemberRefusesAndTheRunCanReadTheRefusal() throws Exception {
        UUID runId = awaitingApprovalRun();

        answerWith(runId, ALICE, "reject")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("reject"));

        var answer = jdbc.queryForMap(
                "SELECT decision FROM settlement_decisions WHERE member_id = ?", ALICE);

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
    void aRequestWithoutAMemberOrWithAnIdThatIsNotAnId() throws Exception {
        UUID runId = awaitingApprovalRun();

        mvc.perform(post("/v1/runs/{runId}/decisions", runId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        mvc.perform(post("/v1/runs/{runId}/decisions", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + ALICE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    private ResultActions answer(UUID runId, UUID memberId) throws Exception {
        return mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":\"" + memberId + "\"}"));
    }

    /**
     * An answer that says which one it is.
     *
     * <p>Separate from {@link #answer} rather than a parameter on it, because the request that omits the field is a
     * case worth testing on its own: absent means approve, which is what the operation meant before refusals were
     * expressible.
     */
    private ResultActions answerWith(UUID runId, UUID memberId, String decision) throws Exception {
        return mvc.perform(post("/v1/runs/{runId}/decisions", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":\"" + memberId + "\",\"decision\":\"" + decision + "\"}"));
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
