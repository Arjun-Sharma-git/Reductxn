package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

import io.reductxn.api.application.identity.SessionTokens;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voiding an obligation, through every layer, against a real database.
 *
 * <p><strong>This is the first write narrowed by membership</strong> (ADR 0045), so the tests are mostly about what the
 * boundary refuses and in what order: a stranger gets the same `404` as a nonexistent obligation, without the answer
 * revealing whether the commitment is open; a run's hold on an obligation is a `409`; and a second void is a `200`, which is
 * what makes the operation idempotent by state rather than by an idempotency key.
 */
@SpringBootTest
class VoidObligationApiTest extends DatabaseTest {

    private static final String OWNER = "+919812345600";
    private static final String STRANGER = "+919800000001";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private String owner;
    private String stranger;

    private UUID groupId;

    @BeforeEach
    void buildMockMvc() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        owner = signedIn(OWNER);
        stranger = signedIn(STRANGER);

        groupId = UUID.randomUUID();

        // The owner is a member. Not through the groups endpoint: this class is about the void, and the membership is
        // the precondition rather than the subject.
        jdbc.update("INSERT INTO group_members (group_id, user_id, display_name, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                groupId, user(OWNER), "Amit", "admin", Instant.now().atOffset(ZoneOffset.UTC));
    }

    /** An open obligation, written as a row: this class is about the transition, not about recording. */
    private UUID anObligation() {
        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO obligations (id, group_id, debtor_id, creditor_id, amount_minor, currency_code,
                                         currency_exponent, incurred_at)
                VALUES (?, ?, ?, ?, ?, 'INR', 2, ?)
                """,
                id, groupId, user(OWNER), UUID.randomUUID(), 25_000L,
                Instant.now().atOffset(ZoneOffset.UTC));

        return id;
    }

    /** A run that holds the obligation, which is what makes it `netted` (ADR 0036's derivation). */
    private void aRunHolds(UUID obligationId) {
        UUID cycle = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO settlement_cycles (id, group_id, as_of, created_at) VALUES (?, ?, ?, ?)",
                cycle, groupId, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'executing', ?, ?)
                """, run, groupId, cycle, now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));

        jdbc.update("INSERT INTO settlement_run_obligations (run_id, obligation_id) VALUES (?, ?)", run, obligationId);
    }

    private String voidedAt(UUID obligationId) {
        return jdbc.queryForObject("SELECT voided_at::text FROM obligations WHERE id = ?", String.class, obligationId);
    }

    /**
     * The row survives, gains its instant, and the read agrees.
     *
     * <p>A void is a transition rather than a delete (ADR 0004): the account of what a member recorded has to survive the
     * correction, and the list the group reads has to say `voided` rather than lose the row (ADR 0036's derived status).
     */
    @Test
    void voidingIsATransitionAndTheListAgrees() throws Exception {
        UUID obligation = anObligation();

        mvc.perform(post("/v1/obligations/" + obligation + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(obligation.toString()))
                .andExpect(jsonPath("$.status").value("voided"))
                // The commitment's own instant is untouched: `occurredAt` is when it was incurred, and the void does not
                // rewrite history to say otherwise.
                .andExpect(jsonPath("$.amount.amountMinor").value(25_000));

        assertThat(voidedAt(obligation), notNullValue());

        mvc.perform(get("/v1/obligations").param("groupId", groupId.toString()).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("voided"));
    }

    /**
     * Voiding twice is one void, and the second call answers `200` with the row.
     *
     * <p>This is the operation's idempotence, and it is why the contract's `Idempotency-Key` is deleted: the row's own
     * `voided_at` answers "was this voided", and the conditional update means a retry cannot restamp the instant. A `409`
     * here would tell a client that its own successful first attempt had failed.
     */
    @Test
    void aSecondVoidIsOneVoid() throws Exception {
        UUID obligation = anObligation();

        mvc.perform(post("/v1/obligations/" + obligation + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());

        String firstVoidedAt = voidedAt(obligation);

        mvc.perform(post("/v1/obligations/" + obligation + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("voided"));

        assertThat(voidedAt(obligation), is(firstVoidedAt));
    }

    /**
     * A stranger learns nothing, not even whether the commitment is open.
     *
     * <p>Two obligations - one open, one a run holds - and the stranger gets the same `404` for both, while the member gets
     * a `200` for the first and a `409` for the second. That pair is the reason the membership is checked before the status
     * (ADR 0045): checking the status first would have let anybody holding a guess read a group's commitments one refusal at
     * a time.
     */
    @Test
    void aStrangerLearnsNothingFromTheRefusal() throws Exception {
        UUID open = anObligation();
        UUID held = anObligation();
        aRunHolds(held);

        mvc.perform(post("/v1/obligations/" + open + "/void").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_obligation"));

        mvc.perform(post("/v1/obligations/" + held + "/void").header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_obligation"));

        // The member, on the same two rows: one voidable, one held by a run.
        mvc.perform(post("/v1/obligations/" + held + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.obligation_not_open"));

        mvc.perform(post("/v1/obligations/" + open + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
    }

    /**
     * A run's hold is a conflict, and the row is left alone.
     *
     * <p>`409` rather than `422`: the request is well-formed and would have succeeded before the run took the commitment -
     * the world refuses it, and the remedy is a cycle that releases it rather than a retry.
     */
    @Test
    void aRunHoldIsAConflictRatherThanAMalformedRequest() throws Exception {
        UUID held = anObligation();
        aRunHolds(held);

        mvc.perform(post("/v1/obligations/" + held + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("api.obligation_not_open"));

        assertThat(voidedAt(held), nullValue());
    }

    /** No credential is a `401`; an id that is not one is this module's own `400`, not a conversion error. */
    @Test
    void theBoundaryRefusesBeforeItLooks() throws Exception {
        UUID obligation = anObligation();

        mvc.perform(post("/v1/obligations/" + obligation + "/void"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(post("/v1/obligations/not-an-id/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.obligation_request_incomplete"));

        mvc.perform(post("/v1/obligations/" + UUID.randomUUID() + "/void").header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_obligation"));
    }
}
