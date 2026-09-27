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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The group's history, through every layer, against a real database.
 *
 * <p><strong>The events are written the way the operations that produce them write them</strong>: a membership through the
 * members endpoint, a decision and an invitation as rows, because this build has no endpoint that records a decision under a
 * group (a decision belongs to a run, which the settlement worker creates). The feed's whole job is to read rows written
 * elsewhere, so a test that inserted its own event table would be testing a table that does not exist.
 *
 * <p>The session is arranged with the production {@link SessionTokens}, exactly as {@code GroupsApiTest} does, for the same
 * reason: the token has to be one the API accepts, or the read proves nothing.
 */
@SpringBootTest
class ActivityApiTest extends DatabaseTest {

    private static final String OWNER = "+919812345678";
    private static final String OTHER = "+919800000000";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private String owner;
    private String other;

    /** The users this class signed in, by number, so a test can name the caller it means. */
    private final java.util.Map<String, UUID> users = new java.util.HashMap<>();

    /** The group every test in this class is about, made through the endpoint that makes groups. */
    private UUID groupId;

    @BeforeEach
    void buildMockMvc() throws Exception {
        users.clear();

        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        owner = signedIn(OWNER);
        other = signedIn(OTHER);

        groupId = createdGroup();
    }

    /**
     * A group the owner is in, made through the endpoint that makes groups - so the first event in the feed is the
     * membership the create wrote, which is also the first assertion of the ordering test.
     */
    private UUID createdGroup() throws Exception {
        String body = mvc.perform(post("/v1/groups")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 3B\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

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
     * A decision on a run in this group, written as rows - because there is no endpoint that records one.
     *
     * <p>It takes three tables to make one event: a cycle, a run, and the decision. That is the shape of the worker's
     * ownership rather than this test making work for itself - a decision names a run, a run names its group and its cycle,
     * and {@code settlement_decisions} has no {@code group_id} to write (ADR 0044).
     */
    private void decisionAt(String answer, Instant at) {
        UUID cycle = UUID.randomUUID();
        UUID run = UUID.randomUUID();

        jdbc.update("INSERT INTO settlement_cycles (id, group_id, as_of, created_at) VALUES (?, ?, ?, ?)",
                cycle, groupId, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));

        jdbc.update("""
                INSERT INTO settlement_runs (id, group_id, cycle_id, status, computed_at, created_at)
                VALUES (?, ?, ?, 'awaiting_approval', ?, ?)
                """, run, groupId, cycle, at.atOffset(ZoneOffset.UTC), at.atOffset(ZoneOffset.UTC));

        jdbc.update("INSERT INTO settlement_decisions (run_id, member_id, decision, decided_at) VALUES (?, ?, ?, ?)",
                run, user(OWNER), answer, at.atOffset(ZoneOffset.UTC));
    }

    /** A code, written the way the invites endpoint writes one. */
    private void invitationAt(int uses, Integer maxUses, Instant at) {
        jdbc.update("""
                INSERT INTO invitations (code, group_id, created_at, expires_at, max_uses, uses)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString().substring(0, 8).toUpperCase().replaceAll("[01IO]", "A"),
                groupId,
                at.atOffset(ZoneOffset.UTC),
                at.plus(Duration.ofDays(7)).atOffset(ZoneOffset.UTC),
                maxUses,
                uses);
    }

    /**
     * Three kinds of event, in the order they happened.
     *
     * <p>The membership the create wrote is the oldest, which is what makes this test more than a sort: the first event in a
     * brand-new group is its creator joining, and the feed says so rather than starting empty.
     */
    @Test
    void theFeedIsTheGroupsHistoryNewestFirst() throws Exception {
        Instant base = Instant.now().minus(Duration.ofHours(4));

        // The create wrote the creator's membership at its own clock's instant - "now" - which would sit above these two.
        // Moving it back is what makes the three instants distinct, so the order is asserted rather than incidental.
        jdbc.update("UPDATE group_members SET joined_at = ? WHERE group_id = ? AND user_id = ?",
                base.atOffset(ZoneOffset.UTC), groupId, user(OWNER));

        invitationAt(1, 3, base.plus(Duration.ofHours(1)));
        decisionAt("approve", base.plus(Duration.ofHours(2)));

        mvc.perform(get("/v1/groups/" + groupId + "/activity").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].kind").value("decision_recorded"))
                .andExpect(jsonPath("$[0].decision").value("approve"))
                .andExpect(jsonPath("$[0].memberId").value(user(OWNER).toString()))
                .andExpect(jsonPath("$[1].kind").value("invitation_created"))
                .andExpect(jsonPath("$[1].uses").value(1))
                .andExpect(jsonPath("$[1].maxUses").value(3))
                .andExpect(jsonPath("$[2].kind").value("member_joined"))
                .andExpect(jsonPath("$[2].memberId").value(user(OWNER).toString()))
                .andExpect(jsonPath("$[2].role").value("admin"));
    }

    /**
     * What the feed cannot say, asserted rather than assumed.
     *
     * <p>An invitation names nobody - the create is unauthenticated in this build, so there is no creator column to read -
     * and a decision records who answered but not what the group calls them. Both absences are the rows' own limits, and a
     * client that showed "Amit invited Rehana" would be inventing a fact. The code itself is absent too: a feed that listed
     * live codes would be a page somebody's shoulder could read (ADR 0044).
     */
    @Test
    void theFeedDoesNotInventWhatTheRowsDoNotSay() throws Exception {
        invitationAt(0, null, Instant.now());
        decisionAt("reject", Instant.now());

        String page = mvc.perform(get("/v1/groups/" + groupId + "/activity")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The invitation: no member, no name, no code, and an unbounded max_uses is absent rather than zero.
        String invitation = JsonPath.read(page, "$[?(@.kind == 'invitation_created')]").toString();

        assertThat(invitation.contains("memberId"), is(false));
        assertThat(invitation.contains("displayName"), is(false));
        assertThat(invitation.contains("maxUses"), is(false));
        assertThat(invitation.contains("code"), is(false));

        // The decision: who answered, and no name for them.
        String decision = JsonPath.read(page, "$[?(@.kind == 'decision_recorded')]").toString();

        assertThat(decision.contains("displayName"), is(false));
        assertThat(decision.contains("reject"), is(true));
    }

    /**
     * A group's history is its members', and the refusal is the group read's own.
     *
     * <p>One code for "no such group" and "not a group you are in" - and a feed is the read that most needs the sameness,
     * because it is the one that names people. No credential at all is a `401`, which is checked first.
     */
    @Test
    void theFeedIsForMembersOnly() throws Exception {
        mvc.perform(get("/v1/groups/" + groupId + "/activity"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(get("/v1/groups/" + groupId + "/activity").header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(get("/v1/groups/not-an-id/activity").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /**
     * The page size is clamped, and a limit that is not a number is a request that cannot be answered.
     *
     * <p>Zero is clamped up rather than refused: the contract's schema declares a maximum and no minimum, so a client that
     * sent `0` asked for nothing rather than sent something invalid - and a page of zero events is not a page. `abc` is
     * {@code api.request_incomplete} with the field named, which is this module's vocabulary for a malformed parameter.
     */
    @Test
    void theLimitIsClampedAndParsed() throws Exception {
        invitationAt(0, null, Instant.now());
        decisionAt("approve", Instant.now());

        mvc.perform(get("/v1/groups/" + groupId + "/activity?limit=1").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/v1/groups/" + groupId + "/activity?limit=0").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // The ceiling is the contract's 200, so a request for everything answers with the ceiling rather than everything.
        mvc.perform(get("/v1/groups/" + groupId + "/activity?limit=100000").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/v1/groups/" + groupId + "/activity?limit=abc").header("Authorization", "Bearer " + owner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /**
     * A group that exists only as rows has a history, and it needs no {@code groups} row.
     *
     * <p>This is ADR 0043's additive table seen from the feed: a group id with a membership and nothing else is a group this
     * build really contains, and the read serves it. The alternative - requiring a group row - would have made every one of
     * those groups invisible in the one place a member can see what has happened.
     */
    @Test
    void aGroupWithNoRowOfItsOwnHasAHistory() throws Exception {
        UUID bare = UUID.randomUUID();

        jdbc.update("INSERT INTO group_members (group_id, user_id, display_name, role, joined_at) VALUES (?, ?, ?, ?, ?)",
                bare, user(OTHER), "Rehana", "member", Instant.now().atOffset(ZoneOffset.UTC));

        mvc.perform(get("/v1/groups/" + bare + "/activity").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("member_joined"))
                .andExpect(jsonPath("$[0].displayName").value("Rehana"));
    }
}
