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
 * Reading a run: the projection a member approves against, and the thing that makes consent informed.
 *
 * <p><strong>The assertions are about what this module may *say*, which is narrower than what the contract's schema
 * can carry.</strong> Every figure here is a row the worker wrote or a count of such rows; nothing is a judgement
 * over them. So the tests pin the figures, the order and the labels - and, just as deliberately, the *absences*:
 * {@code reportingTotals} (it needs a group's reporting currency and groups do not exist), {@code fxRequirements}
 * (the *requirement*, which is a different fact from the outstanding amount the worker's gate conclusion carries -
 * this reader projects the gap a group has to close, not the total the plan asked for) and {@code executedAt} (no
 * instant is recorded when a run reaches {@code done}) are fields this reader does not fill, and asserting they are
 * absent is what stops one being added by accident and looking authoritative. ADR 0021 is the decision that put them
 * outside this module's reach.
 *
 * <p><strong>{@code gates} is the one judgement here, and it is here because the worker wrote it down.</strong>
 * "One approval short" was the sentence this read model could not say while it reported answers and a status: whose
 * arithmetic answers it is the run's, and this module may not derive it (ADR 0021). {@code settlement_run_gates}
 * now holds the verdict, so the tests below assert the projection - including that a run nobody has looked at yet
 * has *no* verdict rather than an invented one.
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

    /** The caller: one of the run's own participants, because a run is read by a member of its group (ADR 0054). */
    private String alice;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        alice = signedIn("+919812345690", ALICE);
        memberRow(GROUP, ALICE, "Alice");
    }

    /** The caller's request: every run read needs a session and a membership since ADR 0054. */
    private MockHttpServletRequestBuilder read(String path, Object runId) {
        return get(path, runId).header("Authorization", "Bearer " + alice);
    }

    @Test
    void aWaitingRunReadsBackWithItsFiguresAndItsAnswers() throws Exception {
        UUID runId = run("awaiting_approval");
        partition(runId, "INR", 2, 500_000L, 300_000L, 400L, 1_200L);
        transfer(runId, "INR", ALICE, BORIS, 300_000L, "run:" + runId + ":inr:0");
        transfer(runId, "INR", ALICE, BORIS, 1_000L, "run:" + runId + ":inr:1");
        answer(runId, ALICE, "approve", AS_OF);

        mvc.perform(read("/v1/runs/{runId}", runId))
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
    void theGatesAreProjectedAsTheWorkerWroteThemAndNotRecomputed() throws Exception {
        UUID runId = run("awaiting_approval");
        answer(runId, ALICE, "approve", AS_OF);
        answer(runId, BORIS, "reject", AS_OF);

        // Two answers that contradict each other, so a reader that recomputed anything would disagree with every
        // figure below: the verdict says the quorum is not met and one debtor still has to confirm, and this module
        // reports exactly that. Written by SQL as the worker writes it, because this deployable cannot conclude
        // anything (see the class comment) - and the worker's GateConclusionTest asserts its writer produces these
        // columns.
        gates(runId, "awaiting_approval", false, 1, 3, 2, true, AS_OF);

        mvc.perform(read("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates.quorum.met").value(false))
                .andExpect(jsonPath("$.gates.quorum.counted").value(1))
                .andExpect(jsonPath("$.gates.quorum.required").value(3))
                .andExpect(jsonPath("$.gates.quorum.debtorsStillToConfirm").value(2))
                .andExpect(jsonPath("$.gates.funding.satisfied").value(true))
                .andExpect(jsonPath("$.gates.funding.outstanding", hasSize(0)))
                .andExpect(jsonPath("$.gates.concludedAt").exists())
                // `status` is the version of the run the verdict belongs to, so a client can tell a current
                // conclusion from one that outlived its run.
                .andExpect(jsonPath("$.gates.status").value("awaiting_approval"));
    }

    @Test
    void whatIsStillMissingIsPerCurrencyAndOnlyTheGaps() throws Exception {
        UUID runId = run("awaiting_fx_funding");
        gates(runId, "awaiting_fx_funding", true, 2, 2, 0, false, AS_OF);
        shortfall(runId, "INR", 2, 12_500L);
        shortfall(runId, "JPY", 0, 224L);

        mvc.perform(read("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gates.funding.satisfied").value(false))
                .andExpect(jsonPath("$.gates.funding.outstanding", hasSize(2)))
                .andExpect(jsonPath("$.gates.funding.outstanding[0].currency").value("INR"))
                .andExpect(jsonPath("$.gates.funding.outstanding[0].amountMinor").value(12_500))
                .andExpect(jsonPath("$.gates.funding.outstanding[1].currency").value("JPY"))
                .andExpect(jsonPath("$.gates.funding.outstanding[1].amountMinor").value(224))
                // Nothing adds rupees to yen (ADR 0012 point 4): the amounts arrive as rows with their own
                // currency, and a covered currency is absent rather than a zero in the list.
                .andExpect(jsonPath("$.gates.quorum.met").value(true));
    }

    @Test
    void aRunTheWorkerHasNotLookedAtYetHasNoGatesRatherThanAnInventedVerdict() throws Exception {
        UUID runId = run("awaiting_approval");

        mvc.perform(read("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                // Absent until the worker concludes something: "nobody has looked" and "the gates are satisfied"
                // are different facts, and answering the first with the second would be inventing a verdict.
                .andExpect(jsonPath("$.gates").doesNotExist());
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

        mvc.perform(read("/v1/runs/{runId}/transfers", runId))
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

        mvc.perform(read("/v1/runs/{runId}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("computing"))
                .andExpect(jsonPath("$.partitions", hasSize(0)))
                .andExpect(jsonPath("$.answers", hasSize(0)));

        mvc.perform(read("/v1/runs/{runId}/transfers", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void aRunThatDoesNotExistIsNotFoundOnBothEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();

        mvc.perform(read("/v1/runs/{runId}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));

        mvc.perform(read("/v1/runs/{runId}/transfers", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));
    }

    /**
     * A run in a group the caller is not in answers what a run id that matches nothing answers (ADR 0054).
     *
     * <p><strong>One code for two conditions, and the reason is that the alternative is an oracle.</strong> A second
     * code for "not yours" would let a caller holding a run uuid learn that the run exists - and this read names the
     * plan, its amounts and its answers, which is the most a run id can be worth to somebody outside the group. The
     * refusal is `api.no_such_run` rather than `api.no_such_group`, which is the mirror of what the group reads do and
     * is deliberate: the code a run-scoped route answers names the id the caller sent and nothing else.
     *
     * <p>The write endpoints that read a run first get this answer too, since they share this reader - which is why
     * `POST /runs/{runId}/decisions` and `.../execute` refuse a stranger with `no_such_run` where ADR 0053 chose
     * `no_such_group`: that refusal's own prose named the group id it could not find, so an unauthenticated request
     * holding a run uuid was told which group the run belonged to.
     */
    @Test
    void aRunInAGroupThatIsNotTheCallersIsNotFound() throws Exception {
        UUID runId = run("awaiting_approval");

        mvc.perform(get("/v1/runs/{runId}", runId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        String stranger = signedIn("+919812345691", new UUID(0L, 91L));

        mvc.perform(get("/v1/runs/{runId}", runId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));

        mvc.perform(get("/v1/runs/{runId}/transfers", runId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_run"));
    }

    @Test
    void anIdThatIsNotAnIdIsRefusedRatherThanLookedUp() throws Exception {
        mvc.perform(read("/v1/runs/{runId}", "not-a-uuid"))
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

    /**
     * The worker's gate conclusion, written exactly as {@code JooqRuns.recordGates} writes it.
     *
     * <p>By SQL, because this module cannot conclude anything: whether a run has quorum is the run's arithmetic, and
     * a fixture that computed it here would be the duplicate implementation the read model refuses (ADR 0021). The
     * other deployable's {@code GateConclusionTest} asserts that its writer produces these columns and that they
     * agree with the verdicts it derives, so the two tests pin one row from both ends.
     */
    private void gates(UUID runId, String status, boolean quorumMet, int counted, int required,
            int debtorsStillToConfirm, boolean fundingSatisfied, Instant concludedAt) {

        jdbc.update("""
                INSERT INTO settlement_run_gates (run_id, status, quorum_met, approvals_counted, approvals_required,
                                                  debtors_still_to_confirm, funding_satisfied, concluded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                """, runId, status, quorumMet, counted, required, debtorsStillToConfirm, fundingSatisfied,
                concludedAt.atOffset(ZoneOffset.UTC));
    }

    /** One currency's gap, as the worker stores it: a row, with the exponent the amount means. */
    private void shortfall(UUID runId, String currency, int exponent, long amountMinor) {
        jdbc.update("""
                INSERT INTO settlement_run_gate_shortfalls (run_id, currency_code, currency_exponent, amount_minor)
                VALUES (?, ?, ?, ?)
                """, runId, currency, exponent, amountMinor);
    }
}
