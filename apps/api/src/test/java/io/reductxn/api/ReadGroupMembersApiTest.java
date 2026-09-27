package io.reductxn.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The group's members, read back through every layer, against a real database.
 *
 * <p><strong>This is the endpoint the contract promised and the build never had, for several slices.</strong> The
 * client's member catalogue calls it from three screens - the obligations list and its filters, the recording form,
 * and a run's answers - so in development every one of them fell back to showing a uuid, and no suite could see it:
 * the client's tests stub the API, and this module had never asked for members. A promise in a contract with nothing
 * behind it is a bug that both suites agree is fine, which is what makes this test the interesting kind (ADR 0035).
 *
 * <p>The rows are written as rows, through SQL, including the ones a test would otherwise record through the endpoint:
 * since ADR 0048 that write requires a member of the group, and a read test whose fixture had to join first would be
 * counting its fixture. The write has its own class against the same database, and what this read serves is the same rows
 * whoever wrote them.
 *
 * <p><strong>Since ADR 0054 the reader is one of those rows, and the fixture made that cheap rather than awkward.</strong>
 * The caller signs in *as* {@code ASHA}: the same person the fixture writes into the group is the one whose session
 * asks for the list, so no test's expected contents changed - and the assertions are the reason that mattered, because
 * a read test that added a reader of its own would be counting its fixture again, one slice later. What did change is
 * the case that cannot happen any more: a group with **no** members is a group nobody can read, so the test that used
 * to assert an empty list asserts a refusal instead.
 */
@SpringBootTest
class ReadGroupMembersApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);

    private static final UUID ASHA = new UUID(0L, 101L);
    private static final UUID BHAVESH = new UUID(0L, 102L);

    private static final String ASHA_PHONE = "+919812345684";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Asha's session: the caller is one of the group's members, so the lists below are the lists she reads. */
    private String asha;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        asha = signedIn(ASHA_PHONE, ASHA);
    }

    /** The caller's request: every group read needs a session and a membership since ADR 0054. */
    private MockHttpServletRequestBuilder read(String path, Object groupId) {
        return get(path, groupId).header("Authorization", "Bearer " + asha);
    }

    /**
     * A group nobody is in is a group nobody can read - including the person asking about it.
     *
     * <p><strong>This test asserted an empty list until ADR 0054, and the empty list was the problem.</strong> "A
     * group with no members" cannot be read by a member, because reading requires being one - so the only possible
     * answers are a refusal, and the refusal is the same one a group that does not exist gives. A group with no
     * memberships is still a state this build contains (dev data, and a group whose creator's membership was never
     * written), which is why the code has to answer something rather than pretend the case cannot arise.
     */
    @Test
    void aGroupWithNoMembersIsUnreadableRatherThanAnEmptyList() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));
    }

    @Test
    void listsTheGroupWithWhatItCallsEachMember() throws Exception {
        record(GROUP, ASHA, "Asha");
        record(GROUP, BHAVESH, "Bhavesh", "admin");

        mvc.perform(read("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(ASHA.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Asha"))
                // The ordinary member, which is what a request that does not say answers with.
                .andExpect(jsonPath("$[0].role").value("member"))
                .andExpect(jsonPath("$[1].userId").value(BHAVESH.toString()))
                .andExpect(jsonPath("$[1].role").value("admin"))
                // No credit limit. The contract used to promise one and nothing sets or consults it, so the field
                // is gone rather than always absent (ADR 0035).
                .andExpect(jsonPath("$[0].creditLimit").doesNotExist());
    }

    @Test
    void theOrderIsTheOrderTheyJoined() throws Exception {
        // The ids are deliberately reversed against the join order, and chosen small: the read orders by
        // `joined_at, user_id`, so a test whose ids happened to sort the same way would pass whichever of the two the
        // implementation used - the mistake the cycles read made once with a random uuid.
        joinedAt(GROUP, BHAVESH, "Bhavesh", Instant.parse("2026-01-01T00:00:00Z"));
        joinedAt(GROUP, ASHA, "Asha", Instant.parse("2026-02-01T00:00:00Z"));

        mvc.perform(read("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].userId").value(BHAVESH.toString()))
                .andExpect(jsonPath("$[1].userId").value(ASHA.toString()));
    }

    @Test
    void membersWhoJoinedAtTheSameInstantHaveOneOrderRatherThanWhateverPostgresReturns() throws Exception {
        // A seeded group, or a test: several members written at one instant. The tie-break on the id is what makes
        // the order total, and it is asserted because "the rows came back in the order I expected" is exactly the
        // assumption that stops being true on a bigger table.
        Instant together = Instant.parse("2026-03-01T00:00:00Z");

        joinedAt(GROUP, BHAVESH, "Bhavesh", together);
        joinedAt(GROUP, ASHA, "Asha", together);

        mvc.perform(read("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$[0].userId").value(ASHA.toString()))
                .andExpect(jsonPath("$[1].userId").value(BHAVESH.toString()));
    }

    @Test
    void oneGroupsMembersAreNotAnothers() throws Exception {
        record(GROUP, ASHA, "Asha");
        record(OTHER_GROUP, BHAVESH, "Bhavesh");

        mvc.perform(read("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Asha"));
    }

    @Test
    void aMalformedGroupIdIsRefusedWithACode() throws Exception {
        mvc.perform(read("/v1/groups/{groupId}/members", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("groupId")));
    }

    /**
     * The names are the group's, and since ADR 0054 the read asks who wants them.
     *
     * <p>This is the read that names people - three screens in the client consume it - so it is where a stranger's
     * request was most informative before this slice: it answered with the group's membership, or with an empty list
     * that said the group existed and was empty. Neither is an answer to a question a non-member asked.
     */
    @Test
    void theMembersAreTheGroupMembersOnly() throws Exception {
        record(GROUP, ASHA, "Asha");

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        String stranger = signedIn("+919812345685", new UUID(0L, 103L));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));
    }

    /**
     * A member written as a row.
     *
     * <p>This used to go through the endpoint, "so the fixture is the write path rather than a copy of it" - and that
     * stopped being the right call in ADR 0048, when the write became restricted to a member of the group. A read test
     * whose fixture had to join the group first would count its own fixture in every assertion, and the counts are the
     * assertions here. The write's rules have their own class, against the same database (`AddMemberApiTest`); this one
     * is about what the read serves, and what it serves is the same rows whoever wrote them.
     */
    private void record(UUID groupId, UUID userId, String displayName) {
        record(groupId, userId, displayName, "member");
    }

    private void record(UUID groupId, UUID userId, String displayName, String role) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-09-01 00:00:00+00')
                """, groupId, userId, displayName, role);
    }

    private void joinedAt(UUID groupId, UUID userId, String displayName, Instant joined) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, ?, 'member', ?::timestamptz)
                """, groupId, userId, displayName, OffsetDateTime.ofInstant(joined, ZoneOffset.UTC));
    }
}
