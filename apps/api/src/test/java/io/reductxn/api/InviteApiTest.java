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
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final UUID BORIS = new UUID(0L, 11L);

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    /** Built from the framework rather than annotated: Spring Boot 4 no longer ships {@code @AutoConfigureMockMvc}. */
    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
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
        accept(first, ALICE, "Alice").andExpect(status().isCreated());
        accept(second, BORIS, "Boris").andExpect(status().isCreated());
    }

    @Test
    void acceptingACodeJoinsTheGroupUnderTheNameTheInviteeGave() throws Exception {
        String code = create();

        accept(code, ALICE, "Alice Rao")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(ALICE.toString()))
                .andExpect(jsonPath("$.displayName").value("Alice Rao"))
                // Accepting joins as the ordinary member: the invitation carries no role, and a code that conferred
                // `admin` would be a privilege handed out by a default.
                .andExpect(jsonPath("$.role").value("member"));

        // The group now has them, which is the only thing that makes the acceptance meaningful.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].displayName").value("Alice Rao"));
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

        accept(code, ALICE, "Alice").andExpect(status().isCreated());
        accept(code, ALICE, "Alice again").andExpect(status().isOk());

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$", hasSize(1)))
                // The name the group first recorded stands: a second acceptance is not a rename, for the reason the
                // members write is not an upsert - any caller could otherwise rename anybody by asking again.
                .andExpect(jsonPath("$[0].displayName").value("Alice"));

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
        accept("ABCD2345", ALICE, "Alice").andExpect(status().isCreated());

        assertThat(usesOf("ABCD2345"), is(1));

        // The second acceptance by Alice is a success, and the invitation is still spent once - so the code is still
        // available for the person it was meant for.
        accept("ABCD2345", ALICE, "Alice").andExpect(status().isOk());
        assertThat(usesOf("ABCD2345"), is(1));
    }

    /** An invitation that existed and ran out of time. */
    @Test
    void anExpiredInvitationIsRefusedWith410() throws Exception {
        // `WXYZ2345` and not something readable: the table's CHECK refuses `0`, `1`, `I` and `O`, and a code a test
        // invents has to obey the same alphabet a generated one does. (An earlier version of this test used a code with
        // an `I` in it and the constraint refused the seed, which is the constraint doing its job.)
        seedInvite("WXYZ2345", Instant.now().minus(Duration.ofMinutes(1)), null, 0);

        accept("WXYZ2345", ALICE, "Alice")
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                // The code names the condition, and the condition is why this is 410 rather than 404: the invitation
                // existed, and it is the *time* that stopped it.
                .andExpect(jsonPath("$.code").value("api.invite_expired"));

        mvc.perform(get("/v1/groups/{groupId}/members", GROUP)).andExpect(jsonPath("$", hasSize(0)));
    }

    /** An invitation that existed, is still in time, and has been spent as often as it allows. */
    @Test
    void anExhaustedInvitationIsRefusedWith410AndSaysSo() throws Exception {
        seedInvite("USEDUP23", Instant.now().plus(Duration.ofDays(1)), 1, 0);

        accept("USEDUP23", ALICE, "Alice").andExpect(status().isCreated());

        accept("USEDUP23", BORIS, "Boris")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("api.invite_exhausted"));

        // Refused, so nobody was joined: an acceptance that cannot record a membership records nothing at all.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].displayName").value("Alice"));
    }

    /** A code nobody holds. Not 410: there was never an invitation for the client to have used up. */
    @Test
    void aCodeNobodyHoldsIsNotFoundRatherThanGone() throws Exception {
        accept("NOPE2345", ALICE, "Alice")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_invite"));
    }

    /** A code's alphabet is uppercase, and a person who types it in lowercase has made a spelling mistake. */
    @Test
    void aCodeTypedInLowercaseIsTheSameRequest() throws Exception {
        String code = create();

        accept(code.toLowerCase(Locale.ROOT), ALICE, "Alice").andExpect(status().isCreated());
    }

    @Test
    void aMalformedAcceptanceIsRefusedWith400BeforeAnyUseCaseRuns() throws Exception {
        String code = create();

        // No body at all, an id that is not an id, a name that is missing, and a name this API will not store -
        // refused rather than truncated, by the same rule and the same code the members write uses.
        acceptRaw(code, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        acceptRaw(code, "{\"userId\":\"nope\",\"displayName\":\"Alice\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        acceptRaw(code, "{\"userId\":\"" + ALICE + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        acceptRaw(code, "{\"userId\":\"" + ALICE + "\",\"displayName\":\"" + "A".repeat(121) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.member_name_too_long"));

        // And none of it joined anybody or spent anything.
        mvc.perform(get("/v1/groups/{groupId}/members", GROUP)).andExpect(jsonPath("$", hasSize(0)));
        assertThat(usesOf(code), is(0));
    }

    @Test
    void aMalformedGroupIdIsRefusedWith400OnCreation() throws Exception {
        mvc.perform(post("/v1/groups/{groupId}/invites", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /** A code for {@link #GROUP}, from the operation itself - which is what a client actually calls. */
    private String create() throws Exception {
        String body = mvc.perform(post("/v1/groups/{groupId}/invites", GROUP))
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

    private ResultActions accept(String code, UUID userId, String displayName) throws Exception {
        return acceptRaw(code, "{\"userId\":\"" + userId + "\",\"displayName\":\"" + displayName + "\"}");
    }

    private ResultActions acceptRaw(String code, String body) throws Exception {
        var request = post("/v1/invites/{code}/accept", code).contentType(MediaType.APPLICATION_JSON);

        return mvc.perform(body.isEmpty() ? request : request.content(body));
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
