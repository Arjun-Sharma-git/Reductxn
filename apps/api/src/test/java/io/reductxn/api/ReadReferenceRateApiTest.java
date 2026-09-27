package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reference rate, through every layer - the last of the three reads ADR 0035's audit found unserved.
 *
 * <p><strong>These tests are mostly about the one business decision in it</strong> (ADR 0037): this read applies the
 * same freshness policy as locking, so an expired rate is a refusal rather than a number. Two tests carry that. One
 * asserts that a pair whose rate has expired is refused here <em>and</em> refused identically by the recording path,
 * which is the whole claim: the form is not told one thing and the submit another. The other asserts the happy case
 * the other way round - the rate this endpoint serves is the rate a cross-currency obligation is actually locked at -
 * which is what makes the hint worth showing at all.
 *
 * <p>The third answer is the retryable one, and it is asserted as a different status on purpose: a pair nothing has
 * quoted is a {@code 503}, because the server cannot answer yet, and a client that conflated it with the {@code 422}
 * would retry a refusal forever or give up on a pair that will be priced tomorrow.
 */
@SpringBootTest
class ReadReferenceRateApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 9L);
    private static final UUID ASHA = new UUID(0L, 301L);
    private static final UUID BHAVESH = new UUID(0L, 302L);

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
    void servesWhatThePairConvertsAtWithTheAgeOfTheRateAndItsSource() throws Exception {
        freshInrToUsd();

        mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("INR"))
                .andExpect(jsonPath("$.quote").value("USD"))
                .andExpect(jsonPath("$.rateScaled").value(12))
                .andExpect(jsonPath("$.rateScale").value(3))
                .andExpect(jsonPath("$.source").value("test fixture"))
                // The age travels, so a screen can say how old the number it is showing is rather than presenting it
                // as current. This is why the response is the contract's whole FxRate schema and not just a number.
                .andExpect(jsonPath("$.quotedAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    void refusesAnExpiredRateHereExactlyAsTheRecordingPathDoes() throws Exception {
        // Expired an hour ago: the feed has stopped, or it is a weekend. There is a rate and it cannot be used.
        Instant expiredAt = Instant.now().minus(1, ChronoUnit.HOURS);
        rate("INR", 2, "USD", 2, 12, 3, expiredAt.minus(1, ChronoUnit.DAYS), expiredAt);

        mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "USD"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("fx.rate_stale"));

        // And recording a commitment in that pair is refused with the same code, which is the decision: a member
        // learns that this cannot be settled in before filling in the form rather than after.
        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "stale-1")
                        .header("Authorization", "Bearer " + caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USD")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("fx.rate_stale"));
    }

    @Test
    void answersCannotAnswerYetForAPairNothingHasQuoted() throws Exception {
        // No rate row at all: not "no", but "not yet" - tomorrow's publication may price it.
        mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "JPY"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("fx.rate_unavailable"));
    }

    @Test
    void servesTheRateTheRecordingPathThenLocks() throws Exception {
        freshInrToUsd();

        String hint = mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "USD"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/v1/obligations")
                        .header("Idempotency-Key", "hinted-1")
                        .header("Authorization", "Bearer " + caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("USD")))
                .andExpect(status().isCreated())
                // The locked quote's rate is the rate the hint showed. This is the claim the decision rests on: a
                // screen showing a member a number is showing them the number they will get, so "indicative" is a
                // caveat about timing - a rate can expire between the two requests - and not about the arithmetic.
                .andExpect(jsonPath("$.fxQuote.rate.rateScaled").value(rateScaledOf(hint)))
                .andExpect(jsonPath("$.fxQuote.rate.rateScale").value(3))
                .andExpect(jsonPath("$.settleInCurrency").value("USD"));
    }

    @Test
    void refusesACurrencyTheCatalogueDoesNotHoldWithTheRecordingPathsOwnCode() throws Exception {
        // One condition, one code: this module already refuses "a currency it cannot transact" as
        // `api.obligation_unknown_currency`, and a second code for the same fact would be a second thing for a client
        // to branch on. The message is general on purpose - it is about the catalogue, not about obligations.
        mvc.perform(get("/v1/fx/rates").param("base", "XYZ").param("quote", "USD"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.obligation_unknown_currency"));

        mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "XYZ"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.obligation_unknown_currency"));
    }

    @Test
    void refusesAMalformedQueryWithACodeRatherThanAFrameworkError() throws Exception {
        mvc.perform(get("/v1/fx/rates").param("quote", "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(containsString("base")));

        mvc.perform(get("/v1/fx/rates").param("base", "INR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("quote")));

        // A pair of one currency is not a conversion, and answering it with a rate of one would put a fabricated
        // number in front of everything downstream that cannot tell it from a quoted one.
        mvc.perform(get("/v1/fx/rates").param("base", "INR").param("quote", "INR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("other than base")));
    }

    /** A rate a provider could have published an hour ago and which is good for another hour. */
    private void freshInrToUsd() {
        Instant now = Instant.now();
        rate("INR", 2, "USD", 2, 12, 3, now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
    }

    /** ₹1250 settling in USD, which is large enough that the conversion cannot be too small to perform. */
    private static String body(String settleInCurrency) {
        return "{\"groupId\":\"" + GROUP + "\",\"debtorId\":\"" + ASHA + "\",\"creditorId\":\"" + BHAVESH
                + "\",\"amount\":{\"amountMinor\":125000,\"currency\":\"INR\"},\"settleInCurrency\":\""
                + settleInCurrency + "\",\"memo\":\"Rent\"}";
    }

    private static int rateScaledOf(String json) {
        return Integer.parseInt(json.replaceAll(".*\"rateScaled\":(\\d+).*", "$1"));
    }
}
