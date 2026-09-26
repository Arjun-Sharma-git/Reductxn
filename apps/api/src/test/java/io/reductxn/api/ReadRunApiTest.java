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
 * Reading a run: the projection a member approves against, and the thing that makes consent informed.
 *
 * <p><strong>The assertions are about what this module may *say*, which is narrower than what the contract's schema
 * can carry.</strong> Every figure here is a row the worker wrote or a count of such rows; nothing is a judgement
 * over them. So the tests pin the figures, the order and the labels - and, just as deliberately, the *absences*:
 * {@code reportingTotals} (it needs a group's reporting currency and groups do not exist), {@code fxRequirements}
 * (the worker's derivation of what the group must obtain from outside) and {@code executedAt} (no instant is recorded
 * when a run reaches {@code done}) are fields this reader does not fill, and asserting they are absent is what stops
 * one being added by accident and looking authoritative. ADR 0021 is the decision that put them outside this
 * module's reach.
 *
 * <p>The rows arrive as SQL because this module cannot run a settlement and may not call the module that does (RULE
 * B6) - the arrangement the preview's read model and the approval fixture already use. What makes that honest is
 * that the worker pins its own side: the columns, the ordering and the vocabulary are its tests' subject, and these
 * are the reader's.
 */
@SpringBootTest
class ReadRunApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 8L);
    private static final UUID ALICE = new UUID(0L, 80L);
    private static final UUID BORIS = new UUID(0L, 81L);
    private static final UUID CAROL = new UUID(0L, 82L);

    /** The group's clearing account, written the way {@code V501} describes it: a participant, not a member. */
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
    void aWaitingRunReadsBackWithItsFiguresAndItsAnswers() throws Exception {
        UUID runId = run("awaiting_approval");
        partition(runId, "INR", 2, 500_000L, 300_000L, 400L, 1_200L);
        transfer(runId, "INR", ALICE, BORIS, 300_000L, "run:" + runId + ":inr:0");
        transfer(runId, "INR", ALICE, BORIS, 1_000L, "run:" + runId + ":inr:1");
        answer(runId, ALICE, "approve", AS_OF);

        mvc.perform(get("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(runId.toString()))
                .andExpect(jsonPath("$.groupId").value(GROUP.toString()))
                .andExpect(jsonPath("$.status").value("awaiting_approval"))
                .andExpect(jsonPath("$.computedAt").exists())
                // The figures a group reads before approving: magnitudes, the signed net, and what netting saved.
                .andExpect(jsonPath("$.partitions", hasSize(1)))
                .andExpect(jsonPath("$.partitions[0].currency").value("INR"))
                .andExpect(jsonPath("$.partitions[0].gross.amountMinor").value(500_000))
                .andExpect(jsonPath("$.partitions[0].net.amountMinor").value(300_000))
                .andExpect(jsonPath("$.partitions[0].tax.amountMinor").value(400))
                .andExpect(jsonPath("$.partitions[0].taxSaved.amountMinor").value(1_200))
                .andExpect(jsonPath("$.partitions[0].transferCount").value(2))
                // Who has answered, as rows. Not whether it was enough: that is the run's arithmetic.
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.answers[0].memberId").value(ALICE.toString()))
                .andExpect(jsonPath("$.answers[0].decision").value("approve"))
                .andExpect(jsonPath("$.answers[0].decidedAt").exists())
                // The fields this module does not fill, asserted so that adding one is a decision rather than a
                // silent claim: each needs something that does not exist yet (ADR 0021).
                .andExpect(jsonPath("$.reportingTotals").doesNotExist())
                .andExpect(jsonPath("$.fxRequirements").doesNotExist())
                .andExpect(jsonPath("$.executedAt").doesNotExist());
    }

    @Test
    void theTransferListIsWhoPaysWhomInTheTablesOwnOrder() throws Exception {
        UUID runId = run("awaiting_approval");
        partition(runId, "INR", 2, 500_000L, 300_000L, 400L, 1_200L);

        // Inserted in the opposite order to the one they must be reported in. `rail_ref` is derived from the
        // transfer's own coordinates, so it has a stable total order - and V501's comment on the index says the
        // point: a plan whose reported order changed between two reads would make the plan a group approved and the
        // plan a rail executed look different when they are the same.
        transfer(runId, "INR", BORIS, CAROL, 20_000L, "run:" + runId + ":inr:zz");
        transfer(runId, "INR", CAROL, FX_CLEARING, 40_000L, "run:" + runId + ":inr:aa", "failed",
                "rail rejects this account");
        transfer(runId, "INR", ALICE, BORIS, 300_000L, "run:" + runId + ":inr:mm");

        mvc.perform(get("/v1/runs/{runId}/transfers", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[*].railRef", contains(
                        "run:" + runId + ":inr:aa",
                        "run:" + runId + ":inr:mm",
                        "run:" + runId + ":inr:zz")))
                // The leg that crosses currencies is labelled with libs/planning's own model of the clearing
                // account, so a group's FX exposure is never reported as if it were a member's payment.
                .andExpect(jsonPath("$[0].toAccountType").value("fx_clearing"))
                .andExpect(jsonPath("$[1].toAccountType").value("member"))
                .andExpect(jsonPath("$[1].fromUserId").value(ALICE.toString()))
                .andExpect(jsonPath("$[1].toUserId").value(BORIS.toString()))
                .andExpect(jsonPath("$[1].amount.amountMinor").value(300_000))
                .andExpect(jsonPath("$[1].amount.currency").value("INR"))
                .andExpect(jsonPath("$[1].status").value("pending"))
                .andExpect(jsonPath("$[1].failureReason").doesNotExist())
                // The rail's own words, which the row holds; there is no stable failure code to branch on yet.
                .andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].failureReason").value("rail rejects this account"));
    }

    @Test
    void aRunStillBeingComputedReadsBackEmptyRatherThanBroken() throws Exception {
        UUID runId = run("computing");

        mvc.perform(get("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("computing"))
                .andExpect(jsonPath("$.partitions", hasSize(0)))
                .andExpect(jsonPath("$.answers", hasSize(0)));

        mvc.perform(get("/v1/runs/{runId}/transfers", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aRunThatDoesNotExistIsNotFoundOnBothEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(get("/v1/runs/{runId}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));

        mvc.perform(get("/v1/runs/{runId}/transfers", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));
    }

    @Test
    void anIdThatIsNotAnIdIsRefusedRatherThanLookedUp() throws Exception {
        mvc.perform(get("/v1/runs/{runId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /** A cycle and a run in the given state: the shape {@code V501} describes, written by a reader's fixture. */
    private UUID run(String status) {
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        OffsetDateTime at = AS_OF.atOffset(ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, ?::timestamptz, ?::timestamptz)
                """, cycleId, GROUP, at, at);

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                """, runId, GROUP, cycleId, status, at, at);

        return runId;
    }

    private void partition(
            UUID runId, String currency, int exponent, long gross, long net, long tax, long taxSaved) {

        jdbc.update("""
                INSERT INTO settlement_partitions
                    (run_id, currency_code, currency_exponent, gross_minor, net_minor, tax_minor, tax_saved_minor)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, runId, currency, exponent, gross, net, tax, taxSaved);
    }

    private void transfer(UUID runId, String currency, UUID from, UUID to, long amount, String railRef) {
        transfer(runId, currency, from, to, amount, railRef, "pending", null);
    }

    private void transfer(
            UUID runId, String currency, UUID from, UUID to, long amount, String railRef, String status,
            String railDetail) {

        jdbc.update("""
                INSERT INTO settlement_transfers
                    (id, run_id, currency_code, currency_exponent, from_user_id, to_user_id, amount_minor,
                     rail_ref, status, rail_detail, created_at)
                VALUES (?, ?, ?, 2, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                """, UUID.randomUUID(), runId, currency, from, to, amount, railRef, status, railDetail,
                AS_OF.atOffset(ZoneOffset.UTC));
    }

    private void answer(UUID runId, UUID memberId, String decision, Instant at) {
        jdbc.update("""
                INSERT INTO settlement_decisions (run_id, member_id, decision, decided_at)
                VALUES (?, ?, ?, ?::timestamptz)
                """, runId, memberId, decision, at.atOffset(ZoneOffset.UTC));
    }
}
