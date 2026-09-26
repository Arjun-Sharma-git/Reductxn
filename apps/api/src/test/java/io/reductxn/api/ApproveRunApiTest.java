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
 * A member approving a run, through every layer, against a real database.
 *
 * <p><strong>This test's real subject is a row this module does not own.</strong> {@code apps/api} writes into
 * {@code workers/settlement}'s decision rows because the member's surface is here and the run there, with no
 * dependency between the two deployables and no broker in the build (ADR 0020). So the assertions are about the
 * <em>shape</em> of what was written - the run, the member, the word {@code 'approve'}, the instant - because that
 * row is the whole of what crosses. {@code workers/settlement}'s {@code RecordedApprovalsTest} writes the same row
 * with the same SQL and checks that the run's arithmetic judges it, and between them a change to that table fails a
 * test rather than a deployment.
 *
 * <p>A run exists here as a fixture rather than as a settlement: written with SQL, because this module cannot run
 * one and may not call the module that does (RULE B6) - the arrangement the preview's read model already uses for
 * the run it excludes.
 */
@SpringBootTest
class ApproveRunApiTest extends DatabaseTest {

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

        approve(runId, ALICE)
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

        approve(runId, ALICE).andExpect(status().isOk());

        approve(runId, ALICE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.approval_already_answered"));

        assertThat(countAnswersFor(runId))
                .as("still one answer: a decision is final, and the primary key is what makes that true rather "
                        + "than a read-then-write two requests could both pass")
                .isEqualTo(1);
    }

    @Test
    void twoMembersEachAnswerOnce() throws Exception {
        UUID runId = awaitingApprovalRun();

        approve(runId, ALICE).andExpect(status().isOk());
        approve(runId, BORIS).andExpect(status().isOk());

        assertThat(countAnswersFor(runId))
                .as("one row per member, which is what a quorum counts")
                .isEqualTo(2);
    }

    @Test
    void aRunThatDoesNotExistIsNotFoundRatherThanAnswered() throws Exception {
        UUID unknown = UUID.randomUUID();

        approve(unknown, ALICE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.approval_no_such_run"));

        assertThat(countAnswersFor(unknown)).isZero();
    }

    @Test
    void anAnswerTheRunCannotHoldIsRefusedByItsOwnRows() throws Exception {
        UUID runId = awaitingApprovalRun();

        approve(runId, FX_CLEARING)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.approval_not_acceptable"));

        assertThat(countAnswersFor(runId))
                .as("the clearing account is a participant in the run's arithmetic and not a member of it, so the "
                        + "run's own rows refuse its answer. This module reports that rather than deciding it - the "
                        + "membership rules are the run's - and the client gets a code rather than a 500")
                .isZero();
    }

    @Test
    void aRequestWithoutAMemberOrWithAnIdThatIsNotAnId() throws Exception {
        UUID runId = awaitingApprovalRun();

        mvc.perform(post("/v1/runs/{runId}/approve", runId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.approval_incomplete"));

        mvc.perform(post("/v1/runs/{runId}/approve", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"" + ALICE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.approval_incomplete"));
    }

    private ResultActions approve(UUID runId, UUID memberId) throws Exception {
        return mvc.perform(post("/v1/runs/{runId}/approve", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberId\":\"" + memberId + "\"}"));
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
