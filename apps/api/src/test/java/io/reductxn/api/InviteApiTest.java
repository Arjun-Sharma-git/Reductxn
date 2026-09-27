package io.reductxn.api;

import com.jayway.jsonpath.JsonPath;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract's two invite operations, through every layer, against a real database.
 *
 * <p>This is a flow rather than a read, so the tests are about the flow's *rules*: what a code is, what accepting one
 * does, and - the decision the coverage entry named - what it does to somebody the group already has. The answer to that
 * last one is composed from the members write rather than invented here: becoming a member is idempotent, so accepting a
 * code you are already covered by is a success that changed nothing, and an invitation is only spent when somebody
 * actually joins.
 *
 * <p>The refusals get a test each because their statuses are three answers to three questions: is this a code at all
 * (`404`), did it run out of time (`410`, expired), did it run out of uses (`410`, exhausted). Two of those share a
 * status and none of them share a code, which is the point of sending both.
 */
@SpringBootTest
class InviteApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);

    private static final String ALICE_PHONE = "+919812345678";
    private static final String BORIS_PHONE = "+919800000000";
    private static final String CREATOR_PHONE = "+919899999998";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /**
     * Alice's access token and the user id behind it, and Boris's token.
     *
     * <p>Three fields rather than two uuids, because the joiner is the session now: a test names a *caller*, and the id
     * it expects in the response is whatever that session belongs to - which the fixture generated.
     */
    private String alice;
    private UUID aliceId;
    private String boris;

    /**
     * The group's own member, separate from the people accepting codes.
     *
     * <p>It has to be somebody else, and the first version of this fixture got that wrong: making Alice a member so she
     * could create a code turned every acceptance of hers into "the group already has you", which is a {@code 200} - so
     * eleven tests that were about joining stopped testing joining. The creator is who the condition is about (ADR 0048);
     * the invitee is who the invitation is for, and the two are different people in every one of these tests.
     */
    private String creator;
    private UUID creatorId;

    /** Built from the framework rather than annotated: Spring Boot 4 no longer ships {@code @AutoConfigureMockMvc}. */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        alice = signedIn(ALICE_PHONE);
        aliceId = user(ALICE_PHONE);
        boris = signedIn(BORIS_PHONE);

        creator = signedIn(CREATOR_PHONE);
        creatorId = user(CREATOR_PHONE);

        // The creator is in the group, because creating an invitation requires a member (ADR 0048). She is not the
        // invitee: making her one would answer every acceptance below with "already a member".
        memberOf(GROUP, creatorId);
    }

    /** A membership as the table holds it, for the group a caller has to be in before a code exists for it. */
    private void memberOf(UUID group, UUID person) {
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, display_name, role, joined_at)
                VALUES (?, ?, 'Creator', 'admin', TIMESTAMPTZ '2026-09-01 00:00:00+00')
                ON CONFLICT DO NOTHING
                """, group, person);
    }

    @Test
    void createsATypeableCodeThatLastsAWeek() throws Exception {
        Instant before = Instant.now();

        String code = create();

        // Eight symbols from the alphabet the table's CHECK names: no `0`, `1`, `I` or `O`, because a code is
        // transcribed by a person. Asserted against the pattern rather than a length, so a code with a lookalike in it
        // fails here rather than being pasted into a message nobody can type.
        assertThat(code, matchesPattern("^[A-HJ-NP-Z2-9]{8}$"));

        // A week, asserted as two bounds rather than an equality: the expiry is the boundary's clock plus seven days,
        // and pinning the instant would be pinning this test's own scheduling.
        Instant expiresAt = expiresAtOf(code);

        assertThat(ChronoUnit.SECONDS.between(before.plus(Duration.ofDays(7)), expiresAt),
                lessThanOrEqualTo(5L));
        assertThat(ChronoUnit.SECONDS.between(expiresAt, Instant.now().plus(Duration.ofDays(7))),
                lessThanOrEqualTo(5L));
    }

    @Test
    void everyInvitationGetsItsOwnCode() throws Exception {
        String first = create();
        String second = create();

        assertThat(first, not(second));

        // Both are usable, which is what "a code per invitation" has to mean: creation is not a rotation.
        accept(first, alice, "Alice").andExpect(status().isCreated());
        accept(second, boris, "Boris").andExpect(status().isCreated());
    }

    @Test
    void acceptingACodeJoinsTheGroupUnderTheNameTheInviteeGave() throws Exception {
        String code = create();

        accept(code, alice, "Alice Rao")
                .andExpect(status().isCreated())
                // The id is the session's, not the body's: `AcceptInvite` no longer carries a `userId`, and the member
                // recorded is the person the boundary found behind the token.
                .andExpect(jsonPath("$.userId").value(aliceId.toString()))
                .andExpect(jsonPath("$.displayName").value("Alice Rao"))
                // Accepting joins as the ordinary member: the invitation carries no role, and a code that conferred
                // `admin` would be a privilege handed out by a default.
                .andExpect(jsonPath("$.role").value("member"));

        // The group now has them, which is the only thing that makes the acceptance meaningful. Asserted by predicate,
        // because the group's member who created the code is in the list as well - and asserting a position would make
        // this depend on the order two memberships were written in.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId=='" + aliceId + "')].displayName", contains("Alice Rao")));
    }

    /**
     * The decision the coverage entry named: accepting a code for a group that already has you.
     *
     * <p>It is a success that changed nothing, because becoming a member is idempotent - the pair is the table's primary
     * key - and "already one of us" is not a mistake for anybody to be told about (P8). Asserted from both sides: the
     * second answer says {@code 200} rather than {@code 201}, the group still has one row for them, and the invitation
     * was spent once rather than twice.
     */
    @Test
    void acceptingACodeForAGroupYouAreAlreadyInChangesNothingAndSpendsNothing() throws Exception {
        String code = create();

        accept(code, alice, "Alice").andExpect(status().isCreated());
        accept(code, alice, "Alice again").andExpect(status().isOk());

        // The name the group first recorded stands: a second acceptance is not a rename, for the reason the members
        // write is not an upsert - any caller could otherwise rename anybody by asking again. The group holds the member
        // who created the code as well, so the row this is about is found by predicate rather than by position.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$[?(@.userId=='" + aliceId + "')].displayName", contains("Alice")));

        assertThat(usesOf(code), is(1));
    }

    /**
     * And the sharpest form of it: an invitation that may be spent once, and a member who is already in.
     *
     * <p>If the acceptance spent a use before asking whether there was anything to do, Alice's second acceptance would
     * have used up the one-time code and Boris - the person the group actually invited - would be refused. Which is why
     * the order of the questions is the decision: ask about the membership first, spend only to record a joining.
     */
    @Test
    void aOneUseInvitationIsNotSpentBySomebodyTheGroupAlreadyHas() throws Exception {
        seedInvite("ABCD2345", Instant.now().plus(Duration.ofDays(1)), 1, 0);
        accept("ABCD2345", alice, "Alice").andExpect(status().isCreated());

        assertThat(usesOf("ABCD2345"), is(1));

        // The second acceptance by Alice is a success, and the invitation is still spent once - so the code is still
        // available for the person it was meant for.
        accept("ABCD2345", alice, "Alice").andExpect(status().isOk());
        assertThat(usesOf("ABCD2345"), is(1));
    }

    /** An invitation that existed and ran out of time. */
    @Test
    void anExpiredInvitationIsRefusedWith410() throws Exception {
        // `WXYZ2345` and not something readable: the table's CHECK refuses `0`, `1`, `I` and `O`, and a code a test
        // invents has to obey the same alphabet a generated one does. (An earlier version of this test used a code with
        // an `I` in it and the constraint refused the seed, which is the constraint doing its job.)
        seedInvite("WXYZ2345", Instant.now().minus(Duration.ofMinutes(1)), null, 0);

        accept("WXYZ2345", alice, "Alice")
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // The code names the condition, and the condition is why this is 410 rather than 404: the invitation
                // existed, and it is the *time* that stopped it.
                .andExpect(jsonPath("$.code").value("api.invite_expired"));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$", hasSize(1)))
                // The caller and nobody else: the group had her before the request, and the invitation joined no one.
                .andExpect(jsonPath("$[0].userId").value(creatorId.toString()));
    }

    /** An invitation that existed, is still in time, and has been spent as often as it allows. */
    @Test
    void anExhaustedInvitationIsRefusedWith410AndSaysSo() throws Exception {
        seedInvite("USEDUP23", Instant.now().plus(Duration.ofDays(1)), 1, 0);

        accept("USEDUP23", alice, "Alice").andExpect(status().isCreated());

        accept("USEDUP23", boris, "Boris")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("api.invite_exhausted"));

        // Refused, so nobody was joined: an acceptance that cannot record a membership records nothing at all. The
        // group still has the member who created the code, which is why this asks about Alice's row rather than a count.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$[?(@.userId=='" + aliceId + "')].displayName", contains("Alice")));
    }

    /** A code nobody holds. Not 410: there was never an invitation for the client to have used up. */
    @Test
    void aCodeNobodyHoldsIsNotFoundRatherThanGone() throws Exception {
        accept("NOPE2345", alice, "Alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_invite"));
    }

    /** A code's alphabet is uppercase, and a person who types it in lowercase has made a spelling mistake. */
    @Test
    void aCodeTypedInLowercaseIsTheSameRequest() throws Exception {
        String code = create();

        accept(code.toLowerCase(Locale.ROOT), alice, "Alice").andExpect(status().isCreated());
    }

    @Test
    void aMalformedAcceptanceIsRefusedWith400BeforeAnyUseCaseRuns() throws Exception {
        String code = create();

        // No body at all, a name that is missing, and a name this API will not store - refused rather than truncated,
        // by the same rule and the same code the members write uses. What is no longer here is a malformed `userId`:
        // that field left the contract with the identity gap it was propping up, and a body still carrying one is
        // answered as though it did not (the test below is the one that says so).
        acceptRaw(code, alice, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        acceptRaw(code, alice, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        acceptRaw(code, alice, "{\"displayName\":\"" + "A".repeat(121) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.member_name_too_long"));

        // And none of it joined anybody or spent anything. One member, because the caller is one: what these refusals
        // must not do is add somebody, so the assertion is that the group has nobody *new*.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value(creatorId.toString()));
        assertThat(usesOf(code), is(0));
    }

    /**
     * The identity gap this operation documented, closed the way its own description said it would be.
     *
     * <p>The paragraph used to read "there is no `GET /me` and no authentication yet, so the server cannot know who is
     * holding the code", and then promised: "when sign-in exists, this body disappears and the acceptance becomes about
     * the caller". Sign-in exists now, so a code with no session is a `401` - and a body that still names somebody joins
     * the session instead, which is the assertion the second test pins.
     */
    @Test
    void anAcceptanceWithoutASessionIsRefusedWith401() throws Exception {
        String code = create();

        for (String header : new String[] {null, "Bearer not-a-token"}) {
            var request = post("/v1/invites/{code}/accept", code)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"displayName\":\"Alice\"}");

            mvc.perform(header == null ? request : request.header("Authorization", header))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("api.not_signed_in"));
        }

        // Refused before the write, so nothing joined and nothing was spent: an acceptance the server cannot attribute
        // to a person records no membership, and a use spent by nobody would cost somebody else the invitation. The only
        // member is the one who created the code, and this asserts she is still the only one.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userId").value(creatorId.toString()));
        assertThat(usesOf(code), is(0));
    }

    @Test
    void theJoinerIsTheSessionAndAUserIdInTheBodyIsIgnored() throws Exception {
        String code = create();

        // The body names Boris while Alice's token is what arrives. It joins Alice: the field is not an assertion the
        // server trusts any more, it is a field the contract removed, and a client that still sends one is not refused
        // for it - ignoring it protects nothing that refusing it would protect, and breaks a client that works.
        acceptRaw(code, alice, "{\"userId\":\"" + user(BORIS_PHONE) + "\",\"displayName\":\"Alice\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(aliceId.toString()));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP).header("Authorization", "Bearer " + creator))
                .andExpect(jsonPath("$[?(@.userId=='" + aliceId + "')].displayName", contains("Alice")));
    }

    @Test
    void aMalformedGroupIdIsRefusedWith400OnCreation() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/invites", "not-a-uuid")
                        .header("Authorization", "Bearer " + creator))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /**
     * Who may hand out a code, which is the policy the contract's description used to leave open.
     *
     * <p>It said, in as many words: "Anyone can create an invitation, and that is the recorded identity gap rather than a
     * policy." The gap is closed now (ADR 0048), so the policy is this: a member of the group, because an invitation is a
     * link a group gives to somebody it trusts.
     */
    @Test
    void aStrangerCannotCreateAnInvitationForAGroup() throws Exception {
        String stranger = signedIn("+919899999999");

        mvc.perform(post("/v1/groups/{groupId}/invites", GROUP)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        // Nothing minted: a refused creation leaves no code behind for anybody to find.
        Integer codes = jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class);

        assertThat(codes == null ? 0 : codes, is(0));
    }

    @Test
    void aCreationWithNoSessionIsRefusedWith401() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/invites", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        // Nothing minted, which is the difference between a refusal and a code somebody could have found.
        Integer codes = jdbc.queryForObject("SELECT count(*) FROM invitations", Integer.class);

        assertThat(codes == null ? 0 : codes, is(0));
    }

    /**
     * A code reads back as the group it joins, and it reads back **without a session**.
     *
     * <p><strong>This is the operation ADR 0054 added, and the no-session part is the operation rather than a
     * shortcut.</strong> The person who asks is the person the link was sent to, who has not signed in yet: the code is
     * the capability they were given, so a preview readable by whoever holds it is exactly as private as the link
     * itself. What it carries is deliberately thin - the group's id and how many members it has, and nothing about
     * amounts, names or commitments.
     *
     * <p>{@code group.name} is absent here rather than null, and that is the state this build contains: this class's
     * group is a bare uuid with a membership and an invitation, and no {@code groups} row of its own (ADR 0043). The
     * next test is the same read over a group that has a name.
     */
    @Test
    void aCodeReadsBackAsTheGroupItJoinsWithoutASession() throws Exception {
        String code = create();

        mvc.perform(get("/v1/invites/{code}", code))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.expiresAt").exists())
                // Absent, not null: the schema's optional property, and the same reading the creation's response needs.
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andExpect(jsonPath("$.group.id").value(GROUP.toString()))
                .andExpect(jsonPath("$.group.memberCount").value(1))
                .andExpect(jsonPath("$.group.name").doesNotExist());
    }

    /**
     * A preview names the group when the group has a name, which is the fact that makes a link worth following.
     *
     * <p>The group is created through the endpoint that creates groups, and the code through the endpoint that creates
     * codes - so the preview's join is exercised against rows two other operations wrote, rather than against a fixture
     * that agrees with it by construction.
     */
    @Test
    void aPreviewNamesTheGroupWhenTheGroupHasARow() throws Exception {
        String created = mvc.perform(post("/v1/groups")
                        .header("Authorization", "Bearer " + creator)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flat 3B\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID named = UUID.fromString(JsonPath.read(created, "$.id"));

        String code = JsonPath.read(mvc.perform(post("/v1/groups/{groupId}/invites", named)
                        .header("Authorization", "Bearer " + creator))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.code");

        mvc.perform(get("/v1/invites/{code}", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group.id").value(named.toString()))
                .andExpect(jsonPath("$.group.name").value("Flat 3B"))
                // The creator's own membership, written by the create: one member, and the invitee is not one yet.
                .andExpect(jsonPath("$.group.memberCount").value(1));
    }

    /**
     * A code that has run out is a `410` before anybody types a name, and the two ways it can run out are told apart.
     *
     * <p>The same pair of codes the acceptance refuses with, from the same conditions, and the same reasoning (ADR
     * 0041): "ask for a new code" and "that one has already been used" are different sentences, so a reader is told
     * which one happened. This is a preview's whole value in that state - it is the difference between a person
     * discovering a stale link on the page they arrived at and discovering it after they filled in a form.
     */
    @Test
    void aPreviewOfACodeThatHasRunOutSaysWhichWay() throws Exception {
        seedInvite("EXPRD234", Instant.now().minus(Duration.ofDays(1)), null, 0);
        seedInvite("USEDUP22", Instant.now().plus(Duration.ofDays(1)), 1, 1);

        mvc.perform(get("/v1/invites/{code}", "EXPRD234"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("api.invite_expired"));

        mvc.perform(get("/v1/invites/{code}", "USEDUP22"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("api.invite_exhausted"));
    }

    /** A code nobody holds is a `404`, and it is the same answer whether the code was mistyped or never existed. */
    @Test
    void aPreviewOfACodeNobodyHoldsIsNotFound() throws Exception {
        mvc.perform(get("/v1/invites/{code}", "ZZZZ9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_invite"));
    }

    /** A code for {@link #GROUP}, from the operation itself - which is what a client actually calls. */
    private String create() throws Exception {
        String body = mvc.perform(post("/v1/groups/{groupId}/invites", GROUP)
                        .header("Authorization", "Bearer " + creator))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                // No limit, said by the schema's optional property being absent rather than null: an invitation is
                // stopped by time rather than by a count, and a client that saw `0` would read it as "unusable".
                .andExpect(jsonPath("$.maxUses").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(body, "$.code");
    }

    /** An acceptance as a client sends one: the caller's token, and the name the group will use. */
    private ResultActions accept(String code, String caller, String displayName) throws Exception {
        return acceptRaw(code, caller, "{\"displayName\":\"" + displayName + "\"}");
    }

    /** The same request with a body written by hand, and `null` for the caller when the test means "no session". */
    private ResultActions acceptRaw(String code, String caller, String body) throws Exception {
        var request = post("/v1/invites/{code}/accept", code).contentType(MediaType.APPLICATION_JSON);
        var authorized = caller == null ? request : request.header("Authorization", "Bearer " + caller);

        return mvc.perform(body.isEmpty() ? authorized : authorized.content(body));
    }

    /**
     * An invitation as the table holds it, for the two states creation cannot reach: one that has expired and one that
     * has been spent.
     *
     * <p>Written with SQL because there is no operation that produces either: a seven-day window is the service's
     * decision and waiting one out is not a test, and a limit cannot be requested at all because the contract's creation
     * operation has no body to ask in.
     */
    private void seedInvite(String code, Instant expiresAt, Integer maxUses, int uses) {
        OffsetDateTime at = expiresAt.atOffset(ZoneOffset.UTC);

        jdbc.update("""
                INSERT INTO invitations (code, group_id, created_at, expires_at, max_uses, uses)
                VALUES (?, ?, ?, ?, ?, ?)
                """, code, GROUP, expiresAt.minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC), at, maxUses, uses);
    }

    private int usesOf(String code) {
        return jdbc.queryForObject("SELECT uses FROM invitations WHERE code = ?", Integer.class, code);
    }

    private Instant expiresAtOf(String code) {
        return jdbc.queryForObject("SELECT expires_at FROM invitations WHERE code = ?", OffsetDateTime.class, code)
                .toInstant();
    }
}
