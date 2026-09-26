package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

import io.reductxn.api.application.identity.OneTimeCodeHash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four identity operations, through every layer, against a real database.
 *
 * <p>The tests arrange challenges by writing them, and that is the only honest way to reach a code. The code itself is
 * never returned, never logged by the application and never stored in the clear - so a test that could read one from
 * anywhere would be a test proving the very thing this slice avoided. What it does instead is seed a challenge with a
 * known code, hashed by the same {@link OneTimeCodeHash} the service uses: one implementation of the rule, used by the
 * producer and by the test that stands in for a person's phone.
 */
@SpringBootTest
class IdentityApiTest extends DatabaseTest {

    private static final String PHONE = "+919812345678";
    private static final String DEVICE = "device-1";

    /** Seeded codes avoid digits that repeat confusingly, and are six digits because a real one is. */
    private static final String CODE = "481516";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void askingForACodeIsAcceptedAndTheCodeIsNowhereInTheAnswer() throws Exception {
        mvc.perform(post("/v1/auth/otp/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + PHONE + "\"}"))
                .andExpect(status().isAccepted())
                // 202 with no body: a message was accepted for delivery, and the code is not in the response because a
                // code in a response is a code anybody reading the response has.
                .andExpect(jsonPath("$").doesNotExist());

        assertThat(challengesFor(PHONE), is(1));

        // And the row holds a hash rather than the code: this is the assertion that would fail if somebody ever
        // "simplified" the storage, and it is the one the whole scheme rests on.
        byte[] stored = storedHash(PHONE);

        assertThat(stored.length, is(32));
        assertThat(Arrays.equals(stored, CODE.getBytes(StandardCharsets.UTF_8)), is(false));
    }

    /**
     * The whole point of the slice: a code becomes a session, and the session knows who is asking.
     *
     * <p>Signing in and then reading `/me` is the flow the client could not do before this slice - and the assertions are
     * the three fields the contract promises, including the KYC tier this deployment can only answer `none` for.
     */
    @Test
    void aCodeBecomesASessionAndTheSessionReadsBackAsMe() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);

        String tokens = verify(PHONE, CODE).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                // Seconds, which the contract says in a description rather than in a format, so it is asserted: half an
                // hour is 1800, and a client that read it as milliseconds would refresh on every request.
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(tokens, "$.accessToken");

        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                // The deployment's name for them is the number, because the number is all it knows.
                .andExpect(jsonPath("$.displayName").value(PHONE))
                .andExpect(jsonPath("$.kycTier").value("none"));
    }

    /** A wrong code is one answer, and the attempt is recorded so the code dies rather than being guessed at forever. */
    @Test
    void aWrongCodeIsRefusedAndCostsAnAttempt() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);

        verify(PHONE, "000000")
                .andExpect(status().isUnauthorized())
                // One code for a wrong code, an expired one, a spent one and a number that never asked: telling a caller
                // which of those it was tells somebody guessing whether there is a challenge worth guessing at.
                .andExpect(jsonPath("$.code").value("api.otp_not_accepted"));

        assertThat(attemptsFor(PHONE), is(1));
    }

    @Test
    void aChallengeWithNoAttemptsLeftIsRefusedEvenWithTheRightCode() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 5);

        verify(PHONE, CODE).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().minus(Duration.ofMinutes(1)), 0);

        verify(PHONE, CODE).andExpect(status().isUnauthorized());
    }

    @Test
    void aCodeTheNumberNeverAskedForIsRefused() throws Exception {
        verify(PHONE, CODE)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.otp_not_accepted"));
    }

    /**
     * A code signs somebody in once.
     *
     * <p>The challenge is spent rather than deleted, which is what makes this true under concurrency as well as in one
     * request: the spend is conditional on the row being unspent, so two verifications of one code produce one session.
     */
    @Test
    void aCodeIsSpentSoItSignsInOnce() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);

        verify(PHONE, CODE).andExpect(status().isOk());
        verify(PHONE, CODE).andExpect(status().isUnauthorized());
    }

    /** One number is one account, however many times it signs in. */
    @Test
    void signingInTwiceIsTheSamePerson() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);
        UUID first = me(verify(PHONE, CODE).andReturn().getResponse().getContentAsString());

        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);
        UUID second = me(verify(PHONE, CODE).andReturn().getResponse().getContentAsString());

        assertThat(first, is(second));
        assertThat(usersCount(), is(1));
    }

    /**
     * Rotation replaces both tokens, and the pair it replaced is dead.
     *
     * <p>The second half is the decision: a rotation that kept the refresh token would leave a credential live for the
     * rest of its life, and one that renewed the access token in place would leave the old access token working. Both
     * are refused here, and the new pair works - which is what "replaces both, always" has to mean to be worth saying.
     */
    @Test
    void refreshingReplacesBothTokensAndKillsTheOldOnes() throws Exception {
        seedChallenge(PHONE, CODE, Instant.now().plus(Duration.ofMinutes(5)), 0);

        String issued = verify(PHONE, CODE).andReturn().getResponse().getContentAsString();
        String oldAccess = JsonPath.read(issued, "$.accessToken");
        String oldRefresh = JsonPath.read(issued, "$.refreshToken");

        String rotated = refresh(oldRefresh).andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();

        String newAccess = JsonPath.read(rotated, "$.accessToken");
        String newRefresh = JsonPath.read(rotated, "$.refreshToken");

        assertThat(newAccess, not(oldAccess));
        assertThat(newRefresh, not(oldRefresh));

        // The old access token is dead, because rotation rewrote the row it was found by - and the new one works.
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + oldAccess))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/me").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());

        // And a replay of the old refresh token is refused rather than producing a second live pair.
        refresh(oldRefresh).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));
    }

    @Test
    void askingForTooManyCodesIsRefusedWith429() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            requestOtp(PHONE).andExpect(status().isAccepted());
        }

        requestOtp(PHONE)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("api.too_many_otp_requests"));

        // Refused before the write: a fourth challenge would be a fourth message this deployment pays for.
        assertThat(challengesFor(PHONE), is(3));
    }

    @Test
    void meWithoutACredentialAndMeWithAnUnknownOneAreTheSameAnswer() throws Exception {
        mvc.perform(get("/v1/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(get("/v1/me").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        // Another scheme is not a bearer token, and it is refused the same way rather than read as one.
        mvc.perform(get("/v1/me").header("Authorization", "Basic " + "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aMissingFieldIsRefusedWith400() throws Exception {
        mvc.perform(post("/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        mvc.perform(post("/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"  \"}"))
                .andExpect(status().isBadRequest());

        // The verify body needs all three: a code proves a number, so a verification without one proves nothing, and a
        // device id is recorded on the session.
        mvc.perform(post("/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"" + PHONE + "\",\"code\":\"" + CODE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        mvc.perform(post("/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /** Asks for a code, the way a client does. */
    private ResultActions requestOtp(String phoneNumber) throws Exception {
        return mvc.perform(post("/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\"}"));
    }

    private ResultActions verify(String phoneNumber, String code) throws Exception {
        return mvc.perform(post("/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phoneNumber\":\"" + phoneNumber + "\",\"code\":\"" + code + "\",\"deviceId\":\"" + DEVICE
                        + "\"}"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    /** The user id behind a token pair, read through the operation a client would use. */
    private UUID me(String tokens) throws Exception {
        String accessToken = JsonPath.read(tokens, "$.accessToken");

        String body = mvc.perform(get("/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /**
     * A challenge with a known code, hashed the way the service hashes one.
     *
     * <p>This is how a test stands in for a phone: the code is never readable from the server, so the only honest way to
     * test verification is to write the row the request would have written - using the same {@link OneTimeCodeHash}, which
     * is the point of it being one class rather than one per caller.
     */
    private void seedChallenge(String phoneNumber, String code, Instant expiresAt, int attempts) {
        byte[] salt = OneTimeCodeHash.salt(new SecureRandom());
        Instant requestedAt = expiresAt.minus(Duration.ofHours(1));

        jdbc.update("""
                INSERT INTO otp_challenges (id, phone_number, code_hash, salt, attempts, requested_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                phoneNumber,
                OneTimeCodeHash.hash(code, salt),
                salt,
                attempts,
                requestedAt.atOffset(ZoneOffset.UTC),
                expiresAt.atOffset(ZoneOffset.UTC));
    }

    private byte[] storedHash(String phoneNumber) {
        return jdbc.queryForObject("SELECT code_hash FROM otp_challenges WHERE phone_number = ?", byte[].class,
                phoneNumber);
    }

    private int attemptsFor(String phoneNumber) {
        return jdbc.queryForObject("SELECT attempts FROM otp_challenges WHERE phone_number = ?", Integer.class,
                phoneNumber);
    }

    private int challengesFor(String phoneNumber) {
        return jdbc.queryForObject("SELECT count(*) FROM otp_challenges WHERE phone_number = ?", Integer.class,
                phoneNumber);
    }

    private int usersCount() {
        return jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
    }
}
