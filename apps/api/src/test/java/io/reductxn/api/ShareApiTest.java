package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The share link: a code that lets somebody look, and joins nobody (ADR 0055).
 *
 * <p><strong>What this class is really pinning is that one instrument has one set of rules.</strong> A share link is the
 * invitation's code - the same alphabet, the same window, the same store - and the only difference is the word in
 * {@code grants}. So the tests that matter are the boundary between the two kinds: a link must not join anybody, an
 * invitation must not show anything, and the preview must say which of the two a holder is holding. Those are refusals,
 * and each one is a sentence a client renders.
 *
 * <p><strong>And that the numbers are the balances read's own.</strong> The positions come from the same composition
 * (ADR 0039's rule, now shared through {@code GroupPositions}), so the test asserts the equality rather than trusting it:
 * a share link that showed a slightly different figure than the group's own screen would be one group told two numbers by
 * one deployment.
 *
 * <p>The rows are SQL, as they are in every read test here: this module cannot run a settlement, and the worker's own
 * tests pin what it writes.
 */
@SpringBootTest
class ShareApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);

    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);

    /** Somebody who owes money and is not a member: a position with no name to show. */
    private static final UUID OUTSIDER = new UUID(0L, 12L);

    private static final String ALICE_PHONE = "+919812345695";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** The member who hands the link out. Alice is also a party to the ledger, so the names below are about her too. */
    private String alice;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        alice = signedIn(ALICE_PHONE, ALICE);
        memberRow(GROUP, ALICE, "Alice");
        memberRow(GROUP, BORIS, "Boris");
    }

    /**
     * A member makes a link, and the link reads without a session.
     *
     * <p>The whole operation in one test, because the two halves are one claim: a code a group hands out is readable by
     * whoever holds it. The read is asked for with **no Authorization header at all**, which is the API-side version of
     * the browser suite's claim that a client sends a token - here the point is that it must not need one.
     */
    @Test
    void aMemberMakesALinkAndItReadsWithoutASession() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);

        String code = makeLink();

        mvc.perform(get("/v1/shared/{code}", code))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.generatedAt").exists())
                .andExpect(jsonPath("$.group.id").value(GROUP.toString()))
                // This group is a bare uuid: no `groups` row, which is a state this build contains (ADR 0043).
                .andExpect(jsonPath("$.group.name").doesNotExist())
                .andExpect(jsonPath("$.group.memberCount").value(2))
                .andExpect(jsonPath("$.positions", hasSize(2)))
                .andExpect(jsonPath("$.positions[*].displayName", containsInAnyOrder("Alice", "Boris")))
                .andExpect(jsonPath("$.positions[*].direction", containsInAnyOrder("pay", "receive")))
                .andExpect(jsonPath("$.positions[*].net.currency", containsInAnyOrder("INR", "INR")));
    }

    /**
     * A position whose id the group's membership does not name carries no name.
     *
     * <p>An obligation recorded against somebody outside the group is a state this build contains - the write refuses a
     * party it cannot resolve, and a member who has left is one the group no longer names - so the read answers with what
     * it knows: the id and the money, and no guess at a name. A placeholder would be indistinguishable from a fact.
     */
    @Test
    void anIdTheGroupDoesNotNameCarriesNoName() throws Exception {
        obligate(GROUP, OUTSIDER, BORIS, 5_000L, "INR", 2);

        String code = makeLink();

        mvc.perform(get("/v1/shared/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions", hasSize(2)))
                .andExpect(jsonPath("$.positions[?(@.memberId=='" + OUTSIDER + "')].displayName").doesNotExist())
                .andExpect(jsonPath("$.positions[?(@.memberId=='" + BORIS + "')].displayName").value("Boris"));
    }

    /** A link lasts the invitation's seven days, because the policy is one policy (ADR 0055). */
    @Test
    void aLinkLastsTheInvitationsSevenDays() throws Exception {
        Instant before = Instant.now();

        String code = makeLink();

        OffsetDateTime expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM invitations WHERE code = ?", OffsetDateTime.class, code);

        assertThat(ChronoUnit.SECONDS.between(before.plus(Duration.ofDays(7)), expiresAt.toInstant()))
                .as("the window starts now and is seven days long, exactly as an invitation's is")
                .isCloseTo(0L, offset(10L));
    }

    /**
     * The positions are the balances read's own computation.
     *
     * <p>This is the claim ADR 0039 makes about every view of a group's positions, and ADR 0055 is what could have broken
     * it: the share link needed the same numbers for a reader who is not a member, and the way to keep one answer was to
     * share the composition ({@code GroupPositions}) rather than to write a second one. Asserted as equality of the two
     * bodies' ids and amounts, in order, because "the same computation" is what those two lists being equal means.
     */
    @Test
    void thePositionsAreTheBalancesReadsOwn() throws Exception {
        obligate(GROUP, ALICE, BORIS, 10_000L, "INR", 2);
        obligate(GROUP, BORIS, ALICE, 4_000L, "INR", 2);

        String code = makeLink();

        String shared = mvc.perform(get("/v1/shared/{code}", code))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String balances = mvc.perform(get("/v1/groups/{groupId}/balances", GROUP)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<java.util.List<String>>read(shared, "$.positions[*].memberId"))
                .isEqualTo(JsonPath.<java.util.List<String>>read(balances, "$[*].userId"));

        assertThat(JsonPath.<java.util.List<Integer>>read(shared, "$.positions[*].net.amountMinor"))
                .isEqualTo(JsonPath.<java.util.List<Integer>>read(balances, "$[*].net.amountMinor"));
    }

    /**
     * A link joins nobody, and the refusal says what the code is rather than pretending it is not there.
     *
     * <p><strong>The one place this slice departs from the `404`-for-two-conditions discipline, deliberately.</strong>
     * {@code no_such_group} and {@code no_such_run} answer "does not exist" and "not yours" identically so a guessable id
     * cannot be tested. A code is neither guessable nor enumerable, and the person asking *holds* it - so a refusal that
     * names the true condition tells them about their own credential rather than about somebody else's. Both halves are
     * asserted: the acceptance refuses, and the link still reads, which is what makes the refusal useful rather than
     * merely honest.
     */
    @Test
    void aLinkJoinsNobodyAndSaysSo() throws Exception {
        String code = makeLink();

        mvc.perform(post("/v1/invites/{code}/accept", code)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.invite_not_a_membership"));

        Integer uses = jdbc.queryForObject("SELECT uses FROM invitations WHERE code = ?", Integer.class, code);

        assertThat(uses).as("a refusal spends no use, so the link still works afterwards").isZero();

        mvc.perform(get("/v1/shared/{code}", code)).andExpect(status().isOk());
    }

    /**
     * An invitation is not a link, and the shared read says so.
     *
     * <p>The same condition from the other end. The code is real, it is in time, and it joins somebody rather than
     * showing them anything - and the last assertion keeps the refusal honest: the same code still answers as an
     * invitation, so what the first request met was a condition rather than a code that had stopped working.
     */
    @Test
    void anInvitationIsNotALinkAndSaysSo() throws Exception {
        String code = JsonPath.read(mvc.perform(post("/v1/groups/{groupId}/invites", GROUP)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.grants").value("membership"))
                .andReturn().getResponse().getContentAsString(), "$.code");

        mvc.perform(get("/v1/shared/{code}", code))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("api.invite_not_a_share"));

        mvc.perform(get("/v1/invites/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants").value("membership"));
    }

    /** The preview says which of the two kinds a holder is holding, which is what a client needs to pick a screen. */
    @Test
    void thePreviewSaysWhatACodeGrants() throws Exception {
        String link = makeLink();

        mvc.perform(get("/v1/invites/{code}", link))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants").value("read"));

        Integer codes = jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class);

        assertThat(codes).as("both kinds are rows in one store, which is the slice's point").isEqualTo(1);
    }

    /**
     * Only a member may hand a link out, and the refusal is the one every other group write uses.
     *
     * <p>ADR 0048's rule unchanged, with ADR 0056 recording the question about the role word: a link is something a group
     * hands out, and a group somebody is not in is not theirs to hand anything out for.
     */
    @Test
    void onlyAMemberMayHandALinkOut() throws Exception {
        String stranger = signedIn("+919899999999");

        mvc.perform(post("/v1/groups/{groupId}/shares", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(post("/v1/groups/{groupId}/shares", GROUP)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(post("/v1/groups/{groupId}/shares", UUID.randomUUID())
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        Integer links = jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class);

        assertThat(links).as("a refused creation leaves no link behind for somebody to find").isZero();
    }

    /**
     * A code nobody holds is `404`, and one that ran out of time is `410`.
     *
     * <p>The two conditions the acceptance already distinguishes, from the same store and decided the same way - which is
     * what "one instrument, one set of rules" means in practice: a share link expires exactly as an invitation does, and a
     * reader is told which of the two happened.
     */
    @Test
    void anUnknownLinkIsNotFoundAndAnExpiredOneIsGone() throws Exception {
        mvc.perform(get("/v1/shared/{code}", "ZZZZ9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_invite"));

        // An expired link, written as a row because creation cannot reach that state.
        jdbc.update("""
                INSERT INTO invitations (code, group_id, grants, created_at, expires_at, max_uses, uses)
                VALUES ('EXPRD234', ?, 'read', TIMESTAMPTZ '2026-08-01 00:00:00+00',
                        TIMESTAMPTZ '2026-08-08 00:00:00+00', NULL, 0)
                """, GROUP);

        mvc.perform(get("/v1/shared/{code}", "EXPRD234"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("api.invite_expired"));
    }

    /** A link, made through the operation a client calls - which is what every test above needs and none should copy. */
    private String makeLink() throws Exception {
        String body = mvc.perform(post("/v1/groups/{groupId}/shares", GROUP)
                        .header("Authorization", "Bearer " + alice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.grants").value("read"))
                .andExpect(jsonPath("$.expiresAt").exists())
                // No limit, said by the optional property being absent: reading through a link spends nothing.
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.code");
    }
}