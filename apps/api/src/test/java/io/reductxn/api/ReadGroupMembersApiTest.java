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
 * <p>The rows are written through the endpoint where the test is about the read, and through SQL where it needs an
 * instant the clock does not choose - the ordering test is the one that cares.
 */
@SpringBootTest
class ReadGroupMembersApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 7L);
    private static final UUID OTHER_GROUP = new UUID(0L, 8L);

    private static final UUID ASHA = new UUID(0L, 101L);
    private static final UUID BHAVESH = new UUID(0L, 102L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void aGroupWithNoMembersAnswersAnEmptyListRatherThanAnError() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listsTheGroupWithWhatItCallsEachMember() throws Exception {
        record(GROUP, ASHA, "Asha");
        record(GROUP, BHAVESH, "Bhavesh", "admin");

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
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

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
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

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$[0].userId").value(ASHA.toString()))
                .andExpect(jsonPath("$[1].userId").value(BHAVESH.toString()));
    }

    @Test
    void oneGroupsMembersAreNotAnothers() throws Exception {
        record(GROUP, ASHA, "Asha");
        record(OTHER_GROUP, BHAVESH, "Bhavesh");

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Asha"));
    }

    @Test
    void aMalformedGroupIdIsRefusedWithACode() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/members", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("groupId")));
    }

    /** A member recorded through the endpoint, so the fixture is the write path rather than a copy of it. */
    private void record(UUID groupId, UUID userId, String displayName) throws Exception {
        record(groupId, userId, displayName, null);
    }

    private void record(UUID groupId, UUID userId, String displayName, String role) throws Exception {
        String body = role == null
                ? "{\"userId\":\"" + userId + "\",\"displayName\":\"" + displayName + "\"}"
                : "{\"userId\":\"" + userId + "\",\"displayName\":\"" + displayName + "\",\"role\":\"" + role + "\"}";

        mvc.perform(post("/v1/groups/{groupId}/members", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    private void joinedAt(UUID groupId, UUID userId, String displayName, Instant joined) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, ?, 'member', ?::timestamptz)
                """, groupId, userId, displayName, OffsetDateTime.ofInstant(joined, ZoneOffset.UTC));
    }
}
