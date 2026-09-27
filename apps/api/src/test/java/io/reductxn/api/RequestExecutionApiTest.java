package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/runs/{runId}/execute}, through every layer, against a real database.
 *
 * <p><strong>What this class can assert, and what it cannot.</strong> It cannot assert that money moved: the transfers
 * are submitted by {@code workers/settlement}'s driver, which reads the request this endpoint writes. What it asserts
 * is the write itself - the flag and the attribution on the run's own row, which is the whole of what crosses the
 * boundary - plus every refusal, because those are decided here. The other half is {@code DriveRunsTest}'s: that a
 * request makes a run due inside its window, and that the attempt spends it.
 *
 * <p><strong>The run is written as rows rather than opened through a cycle.</strong> Opening a cycle is a member's act
 * since ADR 0049 and a run is computed by the worker, so a fixture that went through those paths would be asserting
 * them; this class is about one endpoint and the row it writes. It is the same choice ADR 0048 and ADR 0049 recorded for
 * the two read tests whose fixtures had been using a write.
 *
 * <p><strong>The quorum verdict is inserted rather than derived, deliberately.</strong> {@code settlement_run_gates} is
 * the worker's conclusion - this module may not count a quorum (ADR 0021), and the endpoint only projects what the
 * worker decided - so a test that produced the verdict itself would be testing its own arithmetic instead of the
 * endpoint's reading of somebody else's.
 */
@SpringBootTest
class RequestExecutionApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID CYCLE = new UUID(0L, 70L);
    private static final UUID RUN = new UUID(0L, 71L);

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-09-26T23:59:59Z");

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
     * A signed-in member of the run's group, because a member of the group is who may ask for its settlement to move
     * (ADR 0049, 0050).
     */
    @BeforeEach
    void signInACaller() {
        caller = signedIn(CALLER_PHONE);
        callerId = user(CALLER_PHONE);

        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, 'Caller', 'admin', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, GROUP, callerId);
    }

    @Test
    void aMemberAsksForARunAndTheRequestIsWhereTheWorkerReadsIt() throws Exception {
        aRunIn("awaiting_approval");

        execute(RUN, caller)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(RUN.toString()))
                .andExpect(jsonPath("$.requestedBy").value(callerId.toString()))
                .andExpect(jsonPath("$.requestedAt").isNotEmpty());

        // The receipt is the row. What the worker reads on its next pass is `execute_requested_at`, and the
        // attribution beside it is the half that survives the attempt (ADR 0050) - so both are asserted here rather
        // than only the response, which is a projection of them.
        assertThat(storedRequestedAt()).isNotNull();
        assertThat(storedRequestedBy()).isEqualTo(callerId.toString());
    }

    @Test
    void askingTwiceIsTheSameRequestRatherThanASecondOne() throws Exception {
        aRunIn("awaiting_approval");

        execute(RUN, caller).andExpect(status().isAccepted());
        execute(RUN, caller).andExpect(status().isAccepted());

        // Idempotent by construction: there is one flag, so a second request has nothing to multiply.
        assertThat(storedRequestedAt()).isNotNull();
        assertThat(storedRequestedBy()).isEqualTo(callerId.toString());
    }

    @Test
    void aStrangerCannotAskForARunsSettlement() throws Exception {
        aRunIn("awaiting_approval");
        String stranger = signedIn("+919899999999");

        execute(RUN, stranger)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        assertThat(storedRequestedAt()).as("and nothing was asked for").isNull();
    }

    @Test
    void askingWithNoSessionIsRefusedWith401() throws Exception {
        aRunIn("awaiting_approval");

        mvc.perform(post("/v1/runs/{runId}/execute", RUN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        assertThat(storedRequestedAt()).isNull();
    }

    @Test
    void aRunThisBuildHasNeverHeardOfIsRefusedWith404() throws Exception {
        execute(new UUID(0L, 999L), caller)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));
    }

    @Test
    void aRunThatHasAlreadyMovedIsRefusedWith409() throws Exception {
        aRunIn("executing");

        execute(RUN, caller)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.run_not_ready"));

        assertThat(storedRequestedAt()).as("and nothing was recorded for a run that cannot move").isNull();
    }

    /**
     * The projection, asserted from both sides: the same run is refused while the group is short of approvals and
     * accepted the moment the worker's own verdict says it is not.
     *
     * <p>Nothing about the run changes between the two requests except {@code settlement_run_gates} - and that is the
     * point. This module may not count a quorum, so the test writes the conclusion a worker would have written and
     * asserts that the endpoint follows it.
     */
    @Test
    void aRunWaitingOnItsGroupIsAcceptedOnceTheApprovalsAreThere() throws Exception {
        aRunIn("awaiting_approval");
        gatesConcluding(false);

        execute(RUN, caller)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.run_not_ready"));
        assertThat(storedRequestedAt()).isNull();

        gatesConcluding(true);

        execute(RUN, caller).andExpect(status().isAccepted());
        assertThat(storedRequestedAt()).isNotNull();
    }

    /**
     * A run waiting on currency is not refused, and that is a decision rather than an omission.
     *
     * <p>The contract's {@code 409} names quorum and "already executing"; the driver honours a request as soon as the
     * group brings the currency in, so recording the intent is worth more than refusing it. This is the test that would
     * fail if somebody later widened the refusal to every gate.
     */
    @Test
    void aRunWaitingOnCurrencyIsAccepted() throws Exception {
        aRunIn("awaiting_fx_funding");

        execute(RUN, caller).andExpect(status().isAccepted());

        assertThat(storedRequestedAt()).isNotNull();
    }

    /** The run the endpoint is asked about, and the cycle its migration requires. */
    private void aRunIn(String status) {
        // `OffsetDateTime` rather than `Instant`, with the cast: JDBC has no type to send an `Instant` as, which is
        // how this fails - as a bad-grammar error rather than as a type mismatch.
        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, CYCLE, GROUP, offset(AS_OF), offset(NOW));

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, RUN, GROUP, CYCLE, status, offset(NOW), offset(NOW));
    }

    /** What the worker's gates concluded: {@code met} false is a run its group has not approved enough of. */
    private void gatesConcluding(boolean quorumMet) {
        jdbc.update("""
                INSERT INTO settlement_run_gates (run_id, status, quorum_met, approvals_counted, approvals_required,
                                                  debtors_still_to_confirm, funding_satisfied, concluded_at)
                VALUES (?, 'awaiting_approval', ?, ?, 2, ?, true, ?::timestamptz)
                ON CONFLICT (run_id) DO UPDATE
                    SET quorum_met = excluded.quorum_met,
                        approvals_counted = excluded.approvals_counted,
                        debtors_still_to_confirm = excluded.debtors_still_to_confirm
                """, RUN, quorumMet, quorumMet ? 2 : 1, quorumMet ? 0 : 1, offset(NOW));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private ResultActions execute(UUID runId, String token) throws Exception {
        return mvc.perform(post("/v1/runs/{runId}/execute", runId)
                .header("Authorization", "Bearer " + token));
    }

    private String storedRequestedAt() {
        return jdbc.queryForObject("SELECT execute_requested_at::text FROM settlement_runs WHERE id = ?",
                String.class, RUN);
    }

    private String storedRequestedBy() {
        return jdbc.queryForObject("SELECT execute_requested_by::text FROM settlement_runs WHERE id = ?",
                String.class, RUN);
    }
}
