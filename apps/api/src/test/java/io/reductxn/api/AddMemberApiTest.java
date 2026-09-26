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

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void recordsAMemberAndTheReadServesThemBack() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(ASHA.toString()))
                .andExpect(jsonPath("$.displayName").value("Asha"))
                .andExpect(jsonPath("$.role").value("member"));

        // The row the worker-facing read sees, not just the response: the two go through different code.
        assertThat(countIn("group_members")).isEqualTo(1);

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$[0].displayName").value("Asha"));
    }

    @Test
    void aMemberTheGroupAlreadyHasIsAnsweredWithTheRowThatExists() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());

        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha"));

        assertThat(countIn("group_members")).isEqualTo(1);
    }

    @Test
    void aSecondRequestDoesNotRenameAnybody() throws Exception {
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());

        // A different name for the same person. This is an add, not an upsert: the answer is the row as it stands,
        // and the name the group recorded is not changed by a later caller.
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Somebody Else\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Asha"));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$[0].displayName").value("Asha"));
    }

    @Test
    void onePersonCanBeAMemberOfTwoGroups() throws Exception {
        // The key is the pair, not the person: a member's membership is per group, and a build that keyed on the user
        // alone would make the second group refuse somebody who is already in a group somewhere.
        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}").andExpect(status().isCreated());
        mvc.perform(post("/v1/groups/{groupId}/members", OTHER_GROUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + ASHA + "\",\"displayName\":\"Asha\"}"))
                .andExpect(status().isCreated());

        assertThat(countIn("group_members")).isEqualTo(2);
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

        assertThat(countIn("group_members")).isZero();
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

        assertThat(countIn("group_members")).isZero();
    }

    @Test
    void aNameLongerThanTheContractAllowsIsRefusedRatherThanCutToLength() throws Exception {
        String tooLong = "A".repeat(121);

        record("{\"userId\":\"" + ASHA + "\",\"displayName\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.member_name_too_long"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("121")));

        assertThat(countIn("group_members")).isZero();
    }

    private ResultActions record(String body) throws Exception {
        return mvc.perform(post("/v1/groups/{groupId}/members", GROUP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int countIn(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);

        return count == null ? 0 : count;
    }
}
