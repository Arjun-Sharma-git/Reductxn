package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

import io.reductxn.api.application.identity.SessionTokens;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three group operations, through every layer, against a real database - and the first tests in this module that
 * cannot call an endpoint without a credential.
 *
 * <p><strong>The session is arranged, not signed in for.</strong> These tests are about groups, and going through the OTP
 * flow in each of them would make every failure harder to read and the suite slower (a code is hashed with PBKDF2 on
 * purpose). What they do instead is write the session row the verify operation would have written, using the production
 * {@link SessionTokens} - so the token is one the API accepts, and the read under test is reached the way a client reaches
 * it.
 *
 * <p>Two callers exist throughout, because half of what this slice decides is about <em>whose</em> group something is: one
 * who created a group and one who did not.
 */
@SpringBootTest
class GroupsApiTest extends DatabaseTest {

    private static final String OWNER = "+919812345678";
    private static final String OTHER = "+919800000000";

    private static final String KOLKATA = "Asia/Kolkata";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The users this class signed in, by number, so a test can name the caller it means. */
    private final Map<String, UUID> users = new HashMap<>();

    private String owner;
    private String other;

    @BeforeEach
    void buildMockMvc() {
        users.clear();

        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        owner = signedIn(OWNER);
        other = signedIn(OTHER);
    }

    /**
     * A signed-in person, with a session in the table and a token in hand.
     *
     * <p>The session is written the way the verify operation writes one - same hashing, same windows - because the token is
     * what these reads are *for*. A test that hashed with something else would be arranging a credential the API does not
     * accept, and would prove nothing about the read it is testing.
     */
    private String signedIn(String phone) {
        UUID user = UUID.randomUUID();
        String accessToken = SessionTokens.issue();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO users (id, phone_number, display_name, created_at) VALUES (?, ?, ?, ?)",
                user, phone, phone, now.atOffset(ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO sessions (access_token_hash, refresh_token_hash, user_id, device_id, created_at,
                                      access_expires_at, refresh_expires_at)
                VALUES (?, ?, ?, 'test', ?, ?, ?)
                """,
                SessionTokens.hash(accessToken),
                SessionTokens.hash(SessionTokens.issue()),
                user,
                now.atOffset(ZoneOffset.UTC),
                now.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC),
                now.plus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC));

        users.put(phone, user);

        return accessToken;
    }

    /** The user id behind a number this class signed in. */
    private UUID user(String phone) {
        return users.get(phone);
    }

    /**
     * Creating a group is two writes, and both are asserted.
     *
     * <p>The membership is the half that is easy to forget: {@code GET /groups} answers with the groups somebody belongs
     * to, so a group whose creator was not a member would be invisible to the only person who could have asked for it. The
     * member read is asserted as well, because it is the other screen that shows a membership and its own slice (ADR 0035)
     * is where a reader would go looking if this were wrong.
     */
    @Test
    void creatingAGroupReturnsItAndMakesItsCreatorAMember() throws Exception {
        String group = create(owner, "Flat 3B", "INR", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Flat 3B"))
                .andExpect(jsonPath("$.currency").value("INR"))
                // The contract's default, applied by the boundary when the body does not name one.
                .andExpect(jsonPath("$.maxMembers").value(200))
                // Counted from the membership this call just wrote, so it is one and not zero.
                .andExpect(jsonPath("$.memberCount").value(1))
                // Optional in the schema and absent because nobody chose one: the field is not in the payload at all.
                .andExpect(jsonPath("$.settlementTimezone").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String groupId = JsonPath.read(group, "$.id");

        mvc.perform(get("/v1/groups").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(groupId));

        mvc.perform(get("/v1/groups/" + groupId + "/members").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(user(OWNER).toString()))
                .andExpect(jsonPath("$[0].displayName").value(OWNER))
                // Recorded, not enforced: the creator is the group's admin because that is who can answer "who is in it".
                .andExpect(jsonPath("$[0].role").value("admin"));
    }

    /**
     * The idempotency key is what makes a retry safe rather than a way to end up with two groups.
     *
     * <p>{@code 200} rather than a second {@code 201}, which is the status pair every other write in this build answers a
     * replay with (P8). The id is asserted to be the same one, because that is the property a client depends on: a retry
     * that reported a different group would tell it two exist.
     */
    @Test
    void theSameKeyAnswersWithTheSameGroupAndWritesNothing() throws Exception {
        String same = key();

        String first = create(owner, "Flat 3B", "INR", null, same).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String replay = create(owner, "Flat 3B", "INR", null, same).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String firstId = JsonPath.read(first, "$.id");
        String replayId = JsonPath.read(replay, "$.id");

        assertThat(replayId, is(firstId));
        assertThat(groupsOf(user(OWNER)), is(1));
    }

    /** A different key is a second group, because two households really can be called the same thing. */
    @Test
    void aDifferentKeyIsASecondGroup() throws Exception {
        String first = create(owner, "Flat 3B", "INR", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // The helper mints a key per call, so this second request is a second attempt rather than a retry.
        String second = create(owner, "Flat 3B", "INR", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String firstId = JsonPath.read(first, "$.id");
        String secondId = JsonPath.read(second, "$.id");

        assertThat(secondId, is(not(firstId)));
        assertThat(groupsOf(user(OWNER)), is(2));
    }

    /**
     * The list is the memberships', most recently joined first.
     *
     * <p>That ordering is the only defensible one and it is asserted rather than assumed: a group somebody just created is
     * the first thing they should see, and a list sorted by name would put a group they have not used in months above it.
     */
    @Test
    void theListIsMostRecentlyJoinedFirst() throws Exception {
        String first = createdId(create(owner, "Older", "INR", null));
        String second = createdId(create(owner, "Newer", "INR", null));

        mvc.perform(get("/v1/groups").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
    }

    /**
     * Somebody who belongs to nothing gets an empty list, not a refusal.
     *
     * <p>A person who has just signed in belongs to no group. A client that showed an error for that would be shouting at
     * the ordinary first page, which is why this read has no failure at all.
     */
    @Test
    void somebodyInNoGroupGetsAnEmptyList() throws Exception {
        create(owner, "Not yours", "INR", null).andExpect(status().isCreated());

        mvc.perform(get("/v1/groups").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Every group operation needs a caller, and this is the first place in the module where that is true.
     *
     * <p>ADR 0042 recorded that enforcement everywhere would break every existing caller at once. These operations are new,
     * so there is nothing to break - and this test is what makes the claim real rather than a sentence in a decision: no
     * header, a token nobody issued, and a header with another scheme all answer the one refusal a client already knows how
     * to handle by singing in again.
     */
    @Test
    void theGroupOperationsNeedACaller() throws Exception {
        String groupId = createdId(create(owner, "Flat 3B", "INR", null));

        for (String path : new String[] {"/v1/groups", "/v1/groups/" + groupId}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("api.not_signed_in"));

            mvc.perform(get(path).header("Authorization", "Bearer not-a-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("api.not_signed_in"));

            mvc.perform(get(path).header("Authorization", "Basic abc"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/v1/groups")
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 3B\",\"currency\":\"INR\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        // Refused before the write: a request with no caller does not create a group somebody else would then be in.
        assertThat(groupsOf(user(OWNER)), is(1));
    }

    @Test
    void aMissingFieldOrKeyIsRefusedWith400() throws Exception {
        mvc.perform(post("/v1/groups")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        // The contract makes the idempotency key a required header, so a request without one cannot be answered: there
        // would be no way to tell a retry from a second group.
        mvc.perform(post("/v1/groups")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 3B\",\"currency\":\"INR\"}"))
                .andExpect(status().isBadRequest());
    }

    /** The contract's {@code maxLength} is a promise, so a name that breaks it is refused rather than stored. */
    @Test
    void aNameLongerThanTheContractIsRefused() throws Exception {
        create(owner, "x".repeat(121), "INR", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.group_name_too_long"));

        // One character shorter is fine, so the bound is tested from both sides rather than only where it breaks.
        create(owner, "x".repeat(120), "INR", null).andExpect(status().isCreated());
    }

    /**
     * A currency the catalogue does not hold reuses the code the recording path already answers with.
     *
     * <p>One condition, one code - the reuse ADR 0037 established for the rates read. A client that must handle "that
     * currency does not exist" already knows this code, and a second one for groups would make the same fact two things.
     */
    @Test
    void anUnknownCurrencyIsRefusedWithTheRecordingPathCode() throws Exception {
        create(owner, "Flat 3B", "XYZ", null)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("api.obligation_unknown_currency"));
    }

    /**
     * A timezone is parsed, not stored on trust.
     *
     * <p>{@code "IST"} is the case that matters: it is an abbreviation India, Ireland and Israel all use, and a group
     * holding it would believe it had answered a question that decides which cycle an obligation falls in. The column is
     * nullable exactly so that "not chosen yet" is sayable - which the first test in this class already asserts.
     */
    @Test
    void aTimezoneIsParsedAndAnAbbreviationIsRefused() throws Exception {
        create(owner, "Kolkata", "INR", KOLKATA)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.settlementTimezone").value(KOLKATA));

        create(owner, "Abbreviated", "INR", "IST")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.group_unknown_timezone"));
    }

    /** Cross-currency totals default to the settlement currency, which the contract says and a client depends on. */
    @Test
    void reportingCurrencyDefaultsToTheSettlementCurrency() throws Exception {
        create(owner, "Defaulted", "INR", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportingCurrency").value("INR"));

        mvc.perform(post("/v1/groups")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stated\",\"currency\":\"INR\",\"reportingCurrency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportingCurrency").value("USD"));
    }

    /**
     * The single read answers one code for "no such group" and "not a group you are in".
     *
     * <p>The sameness is a security decision rather than a convenience: telling the two apart would let anybody holding a
     * guess learn which ids are real, which is the enumeration the contract's single {@code 404} already implies. And an id
     * that is not an id at all is a {@code 400}, because a typo is not a permission question.
     */
    @Test
    void readingAGroupNeedsAMembership() throws Exception {
        String groupId = createdId(create(owner, "Flat 3B", "INR", null));

        mvc.perform(get("/v1/groups/" + groupId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Flat 3B"))
                .andExpect(jsonPath("$.memberCount").value(1));

        // The other caller exists and is signed in; what they lack is a membership.
        mvc.perform(get("/v1/groups/" + groupId).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(get("/v1/groups/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(get("/v1/groups/not-an-id").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /**
     * The count is every member, not just the caller.
     *
     * <p>It is the mistake this read is one join away from making: filtering by the caller's membership and counting the
     * same join answers one for every group. A second member is added through the members write, which is where a membership
     * really comes from.
     */
    @Test
    void theMemberCountIsEverybodyInTheGroup() throws Exception {
        String groupId = createdId(create(owner, "Flat 3B", "INR", null));

        mvc.perform(post("/v1/groups/" + groupId + "/members")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + UUID.randomUUID() + "\",\"displayName\":\"Rehana\","
                                + "\"role\":\"member\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/v1/groups/" + groupId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2));
    }

    /** A key per request, because two calls in one test must be two attempts rather than a retry. */
    private static String key() {
        return UUID.randomUUID().toString();
    }

    /** A create request, with the token and a fresh key - two calls here are two attempts, never a retry. */
    private ResultActions create(String token, String name, String currency, String timezone) throws Exception {
        return create(token, name, currency, timezone, key());
    }

    /** The same, with the key named - which is how a test asks for a replay rather than a second group. */
    private ResultActions create(String token, String name, String currency, String timezone, String idempotencyKey)
            throws Exception {

        StringBuilder body = new StringBuilder("{\"name\":\"").append(name).append("\",\"currency\":\"").append(currency)
                .append('"');

        if (timezone != null) {
            body.append(",\"settlementTimezone\":\"").append(timezone).append('"');
        }

        return mvc.perform(post("/v1/groups")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.append('}').toString()));
    }

    /** The id out of a create response, so a test can name the group it just made. */
    private static String createdId(ResultActions created) throws Exception {
        return JsonPath.read(created.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** How many groups this person is a member of, counted in the database rather than read through the API. */
    private int groupsOf(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM group_members WHERE user_id = ?", Integer.class, userId);
    }
}
