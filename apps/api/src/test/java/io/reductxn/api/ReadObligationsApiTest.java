package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The obligations list, through every layer, against a real database.
 *
 * <p><strong>This is the read the app's main screen has always called and this module never served</strong> - the
 * first of the three ADR 0035's audit found, and the worst of them: the obligations screen is the one the whole
 * interface is organised around, its filters have been in the URL since it was built, and until now the only way to
 * see an obligation this API had recorded was to record one.
 *
 * <p><strong>The status is the part worth testing hardest, because it is derived rather than stored.</strong> An
 * obligation's four states come from this module's {@code voided_at} and the settlement worker's runs, so the
 * fixtures write the worker's own rows - a cycle, a run, a link - and the assertions are about the words the contract
 * names. Two of them are about what a *run that gave up* means: a commitment nothing is settling is open, which is
 * the reading the netting preview uses and the reason a link alone is not enough to call something netted.
 */
@SpringBootTest
class ReadObligationsApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);

    private static final UUID ASHA = new UUID(0L, 101L);
    private static final UUID BHAVESH = new UUID(0L, 102L);

    private static final UUID FIRST = new UUID(0L, 201L);
    private static final UUID SECOND = new UUID(0L, 202L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void answersAnEmptyListForAGroupWithNothingInItAndNotAnotherGroupsCommitments() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 125_000, "2026-09-01T00:00:00Z");

        mvc.perform(get("/v1/obligations").param("groupId", OTHER_GROUP.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void servesWhatEachCommitmentIsAndWhoItIsBetween() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 125_000, "2026-09-01T00:00:00Z");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(FIRST.toString()))
                .andExpect(jsonPath("$[0].debtorId").value(ASHA.toString()))
                .andExpect(jsonPath("$[0].creditorId").value(BHAVESH.toString()))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(125_000))
                .andExpect(jsonPath("$[0].amount.currency").value("INR"))
                // What the creditor is to be paid in, which for an ordinary obligation is the debt currency: the
                // contract says this is a claim about the commitment rather than an echo of a request.
                .andExpect(jsonPath("$[0].settleInCurrency").value("INR"))
                .andExpect(jsonPath("$[0].status").value("open"));
    }

    @Test
    void isNewestFirstUnlessTheSortSaysOldestFirst() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        obligation(SECOND, GROUP, BHAVESH, ASHA, 200, "2026-09-02T00:00:00Z");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()))
                .andExpect(jsonPath("$[0].id").value(SECOND.toString()))
                .andExpect(jsonPath("$[1].id").value(FIRST.toString()));

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("sort", "occurredAt"))
                .andExpect(jsonPath("$[0].id").value(FIRST.toString()))
                .andExpect(jsonPath("$[1].id").value(SECOND.toString()));
    }

    @Test
    void aSettledRunMakesItsCommitmentsSettledRatherThanOpen() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        heldByARunThatIs(FIRST, "done");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()))
                .andExpect(jsonPath("$[0].status").value("settled"));

        // And the filter reads the same value the row carries: one derivation, so a page cannot contain a row whose
        // status it would not display.
        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("status", "settled"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("status", "open"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aRunThatIsStillWorkingHoldsItsCommitmentsSoTheyAreNetted() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        heldByARunThatIs(FIRST, "awaiting_approval");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()))
                .andExpect(jsonPath("$[0].status").value("netted"));
    }

    @Test
    void aRunThatGaveUpReleasedItsCommitmentsAndTheyAreOpenAgain() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        // The link exists and the run is over: a run that failed settled nothing, and the commitment it was going to
        // settle is still owed. This is the case the obvious rule ("a link means netted") gets wrong, and the reason
        // the derivation lists the five holding states instead of treating "not done" as holding.
        heldByARunThatIs(FIRST, "failed");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()))
                .andExpect(jsonPath("$[0].status").value("open"));
    }

    @Test
    void filtersByMemberOnEitherSideAndByDate() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        obligation(SECOND, GROUP, BHAVESH, ASHA, 200, "2026-09-10T00:00:00Z");

        // A member reading their own ledger wants what they owe and what they are owed.
        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("memberId", ASHA.toString()))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString())
                        .param("from", "2026-09-09").param("to", "2026-09-10"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(SECOND.toString()));
    }

    @Test
    void refusesAQueryItCannotReadWithACode() throws Exception {
        mvc.perform(get("/v1/obligations"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.obligation_request_incomplete"))
                .andExpect(jsonPath("$.detail").value(containsString("groupId")));

        mvc.perform(get("/v1/obligations").param("groupId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("status", "settling"))
                .andExpect(jsonPath("$.detail").value(containsString("settled")));

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("from", "yesterday"))
                .andExpect(jsonPath("$.detail").value(containsString("yyyy-MM-dd")));

        // A sort this build does not order by is refused rather than ignored, because a silently ignored sort
        // answers a different question from the one that was asked.
        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("sort", "amount"))
                .andExpect(jsonPath("$.detail").value(containsString("-occurredAt")));

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("limit", "many"))
                .andExpect(jsonPath("$.detail").value(containsString("whole number")));
    }

    @Test
    void takesALimitRatherThanEverythingTheGroupHasEverRecorded() throws Exception {
        obligation(FIRST, GROUP, ASHA, BHAVESH, 100, "2026-09-01T00:00:00Z");
        obligation(SECOND, GROUP, ASHA, BHAVESH, 200, "2026-09-02T00:00:00Z");

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("limit", "1"))
                .andExpect(jsonPath("$.length()").value(1));

        // A limit past the maximum is clamped rather than refused - the request is answerable, it is just bigger than
        // this endpoint serves - and a limit of zero is not a limit, so it takes the default instead of answering
        // with no rows.
        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("limit", "9999"))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/v1/obligations").param("groupId", GROUP.toString()).param("limit", "0"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aReplayReportsTheStatusTheListReports() throws Exception {
        // The write path answers with an obligation it already has, and it now derives the status from the same
        // expression the list serves (ADR 0036). Before this slice it answered `open` for anything not voided, so a
        // retry could tell a client a settled commitment was still owed while the list said otherwise.
        keyed(FIRST, "dinner-1");
        heldByARunThatIs(FIRST, "done");

        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "dinner-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + GROUP + "\",\"debtorId\":\"" + ASHA + "\",\"creditorId\":\""
                                + BHAVESH + "\",\"amount\":{\"amountMinor\":4200,\"currency\":\"INR\"},"
                                + "\"memo\":\"Dinner\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(FIRST.toString()))
                .andExpect(jsonPath("$.status").value("settled"));
    }

    /** One commitment with the id a test chose, because a status test has to link a run to it. */
    private void obligation(UUID id, UUID group, UUID debtor, UUID creditor, long amountMinor, String incurredAt) {
        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor,
                                         currency_code, currency_exponent, incurred_at)
                VALUES (?, ?, ?, ?, ?, 'INR', 2, ?::timestamptz)
                """, id, group, debtor, creditor, amountMinor,
                OffsetDateTime.parse(incurredAt).withOffsetSameInstant(ZoneOffset.UTC));
    }

    /** The same, carrying the idempotency key a replay will find it by. */
    private void keyed(UUID id, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor,
                                         currency_code, currency_exponent, memo, idempotency_key, incurred_at)
                VALUES (?, ?, ?, ?, 4200, 'INR', 2, 'Dinner', ?, TIMESTAMPTZ '2026-09-01 00:00:00+00')
                """, id, GROUP, ASHA, BHAVESH, idempotencyKey);
    }

    /**
     * A run in the settlement worker's own tables, holding one commitment.
     *
     * <p>Written here rather than through the worker, because what is under test is this module's read: the worker's
     * state is an input to it, and producing that state through the worker would make this a test of the worker. The
     * cycle comes first because a run references one - the worker's own constraint, not this module's.
     */
    private void heldByARunThatIs(UUID obligationId, String runStatus) {
        UUID cycleId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO settlement_cycles (id, group_id, as_of, created_at)
                VALUES (?, ?, TIMESTAMPTZ '2026-09-20 00:00:00+00', TIMESTAMPTZ '2026-09-20 00:00:00+00')
                """, cycleId, GROUP);

        jdbc.update("""
                INSERT INTO settlement_runs (id, cycle_id, group_id, status, computed_at, created_at)
                VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-09-20 00:00:00+00', TIMESTAMPTZ '2026-09-20 00:00:00+00')
                """, runId, cycleId, GROUP, runStatus);

        jdbc.update("INSERT INTO settlement_run_obligations (run_id, obligation_id) VALUES (?, ?)",
                runId, obligationId);
    }
}
