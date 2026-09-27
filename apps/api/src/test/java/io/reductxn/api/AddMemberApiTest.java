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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recording a member, through every layer, against a real database.
 *
 * <p><strong>The write exists because the read would otherwise be a screen that is empty forever.</strong> The plan
 * has had `GET /groups/{groupId}/members` in the contract for several slices and this build had neither operation;
 * membership is now data (ADR 0035), so something has to record it, and the candidate that needs no identity - the
 * thing this build does not have - is the caller.
 *
 * <p><strong>What this pins is the decision to add rather than upsert.</strong> The pair `(groupId, userId)` is the
 * primary key, so a second request for the same person answers {@code 200} with the row as it stands: idempotent by
 * rule (P8), and no caller can rename anybody by asking again. Renaming belongs to the admin operation a build
 * without identity cannot recognise, and the test that a name is *not* changed is what makes that claim real rather
 * than a sentence in a comment.
 */
@SpringBootTest
class AddMemberApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);
    private static final UUID ASHA = new UUID(0L, 101L);

    private static final String OWNER_PHONE = "+919812345678";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The caller, who is a member of {@link #GROUP} - the condition this write gained in ADR 0048. */
    private String owner;
    private UUID ownerId;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        owner = signedIn(OWNER_PHONE);
        ownerId = user(OWNER_PHONE);

        memberOf(GROUP, ownerId, "Owner");
    }

    /**
     * A membership as the table holds it, because the caller has to be in the group before the group can be added to.
     *
     * <p>{@code ON CONFLICT DO NOTHING} so a test that records the caller's own membership through the endpoint is
     * unchanged by this fixture: the pair is the primary key and adding is an add rather than an upsert.
     */
    private void memberOf(UUID group, UUID person, String name) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, ?, 'admin', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, group, person, name);
    }

    @Test
    void recordsAMemberAndTheReadServesThemBack() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(ASHA.toString()))
                .andExpect(jsonPath("$.displayName").value("Asha"))
                .andExpect(jsonPath("$.role").value("member"));

        // The row the worker-facing read sees, not just the response: the two go through different code. Two rows in the
        // table, because the caller's own membership is a precondition of the write rather than something it wrote.
        assertThat(countIn("group_members")).isEqualTo(2);

        // Asserted by predicate rather than by position: the list now holds the caller as well, and an index would make
        // this test depend on the order two memberships were written in.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$[?(@.userId=='" + ASHA + "')].displayName", contains("Asha")));
    }

    @Test
    void aMemberTheGroupAlreadyHasIsAnsweredWithTheRowThatExists() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());

        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha"));

        assertThat(countIn("group_members")).isEqualTo(2);
    }

    @Test
    void aSecondRequestDoesNotRenameAnybody() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());

        // A different name for the same person. This is an add, not an upsert: the answer is the row as it stands,
        // and the name the group recorded is not changed by a later caller.
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Somebody Else\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha"));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$[?(@.userId=='" + ASHA + "')].displayName", contains("Asha")));
    }

    @Test
    void onePersonCanBeAMemberOfTwoGroups() throws Exception {
        // The key is the pair, not the person: a member's membership is per group, and a build that keyed on the user
        // alone would make the second group refuse somebody who is already in a group somewhere. The caller has to be in
        // the second group as well, which is the same condition the first write met (ADR 0048).
        memberOf(OTHER_GROUP, ownerId, "Owner");

        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());
        mvc.perform(post("/v1/groups/{groupId}/members", OTHER_GROUP)
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}"))
                .andExpect(status().isCreated());

        assertThat(countIn("group_members")).isEqualTo(4);
    }

    @Test
    void anAdminIsRecordedAsOne() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\",\"role\":\"admin\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void aRoleThisBuildDoesNotStoreIsRefusedWithTheWordsItDoes() throws Exception {
        // The schema's vocabulary, which is the table's CHECK. A caller who sent "owner" is told the two words rather
        // than given a generic refusal, because the request was understood and one field is wrong.
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\",\"role\":\"owner\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("admin")));

        assertThat(rowsFor(ASHA)).isZero();
    }

    @Test
    void aMalformedRequestIsRefusedWithACodeAndWritesNothing() throws Exception {
        record("{\"displayName\":\"Asha\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("userId")));

        record("{\"userId\":\"" + ASHA + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("displayName")));

        record("{\"userId\":\"not-a-uuid\",\"displayName\":\"Asha\"}")
                .andExpect(status().isBadRequest());

        assertThat(rowsFor(ASHA)).isZero();
    }

    @Test
    void aNameLongerThanTheContractAllowsIsRefusedRatherThanCutToLength() throws Exception {
        String tooLong = "A".repeat(121);

        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.member_name_too_long"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("121")));

        assertThat(rowsFor(ASHA)).isZero();
    }

    /**
     * The condition, from both sides of it: a signed-in stranger - somebody real, who is simply not in this group - is
     * refused with the one code that does not say which of the two facts it was (ADR 0048).
     *
     * <p>{@code 404} and not {@code 403}, the same reading the void and the activity feed use: telling a caller "not
     * allowed" and "no such group" apart would let anybody holding a guessed id learn that it is real.
     */
    @Test
    void aStrangerCannotAddAnybodyToAGroup() throws Exception {
        String stranger = signedIn("+919899999999");

        mvc.perform(post("/v1/groups/{groupId}/members", GROUP)
                        .header("Authorization", "Bearer " + stranger)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        // Refused before the write, so the stranger added nobody - which is the whole of what the check is for.
        assertThat(rowsFor(ASHA)).isZero();
    }

    @Test
    void anAddWithNoSessionIsRefusedWith401() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/members", GROUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        assertThat(rowsFor(ASHA)).isZero();
    }

    private ResultActions record(String body) throws Exception {
        return mvc.perform(post("/v1/groups/{groupId}/members", GROUP)
                .header("Authorization", "Bearer " + owner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * How many memberships this person has anywhere.
     *
     * <p>What "nothing was written" has to mean now that the caller's own membership is in the table before every test:
     * counting the whole table would count the caller, and a count that includes the fixture is a count of the fixture.
     */
    private int rowsFor(UUID person) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM group_members WHERE user_id = ?", Integer.class, person);

        return count == null ? 0 : count;
    }

    private int countIn(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

        return count == null ? 0 : count;
    }
}
