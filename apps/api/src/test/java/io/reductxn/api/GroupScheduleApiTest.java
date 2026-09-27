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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A group's schedule: when its books close, and how often after that (ADR 0057).
 *
 * <p><strong>What this class pins is that a schedule is a group's property with three operations and three
 * conditions.</strong> A member states it, reads it and stops it; a stranger may do none of the three; an interval
 * outside the range the contract declares is a request the document does not describe; and a due instant that is not in
 * the future is refused rather than becoming a second way to close the books immediately.
 *
 * <p><strong>The one thing it cannot pin is that anything acts on it</strong> - that is the worker, and its own test
 * opens the cycles a due schedule brings about ({@code OpenDueCyclesTest}). The seam between the two is the table: this
 * class asserts what a member's request writes, and the worker's test asserts what a due row becomes.
 */
@SpringBootTest
class GroupScheduleApiTest extends DatabaseTest {

    private static final UUID GROUP = new UUID(0L, 1L);
    private static final UUID ALICE = new UUID(0L, 10L);
    private static final String ALICE_PHONE = "+919812345696";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private String alice;

    @BeforeEach
    void buildMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();

        alice = signedIn(ALICE_PHONE, ALICE);
        memberRow(GROUP, ALICE, "Alice");
    }

    /** A member states a cadence, and both the answer and the read give back what the worker will act on. */
    @Test
    void aMemberSetsAScheduleAndTheReadServesItBack() throws Exception {
        Instant due = due();

        setSchedule(30, due);

        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.everyDays").value(30))
                .andExpect(jsonPath("$.nextDueAt").value(due.toString()))
                // The stamp is the boundary's clock, so a reader can tell whether the statement is current.
                .andExpect(jsonPath("$.setAt").exists());

        assertThat(jdbc.queryForObject("SELECT every_days FROM group_schedules WHERE group_id = ?", Integer.class, GROUP))
                .as("the row the worker scans is the row this request wrote")
                .isEqualTo(30);
    }

    /** Setting it again changes it rather than adding a second one: a group has one schedule. */
    @Test
    void settingItAgainChangesIt() throws Exception {
        Instant first = due();
        Instant second = first.plusSeconds(3_600);

        setSchedule(7, first);
        setSchedule(14, second);

        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.everyDays").value(14))
                .andExpect(jsonPath("$.nextDueAt").value(second.toString()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_schedules WHERE group_id = ?", Integer.class, GROUP))
                .as("one row, replaced - which is what `PUT` and the primary key both mean")
                .isEqualTo(1);
    }

    /** A cadence outside the contract's range is a request the document does not describe. */
    @Test
    void aCadenceOutsideTheRangeIsRefusedWithItsOwnCode() throws Exception {
        Instant due = Instant.now().plusSeconds(3_600);

        for (int everyDays : new int[] {0, -1, 367}) {
            mvc.perform(put("/v1/groups/{groupId}/schedule", GROUP)
                            .header("Authorization", "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"everyDays\":" + everyDays + ",\"nextDueAt\":\"" + due + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("api.schedule_every_days_out_of_range"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_schedules", Integer.class))
                .as("a refused request writes nothing")
                .isZero();
    }

    /**
     * A due instant that is not in the future is refused, because the other operation already closes the books now.
     *
     * <p>Two instants are tried, a past one and exactly "now", because the check is half-open: if the worker can see a
     * row as due, then a row whose instant is "now" is already due - and accepting it would make this operation a way of
     * opening a cycle in the pass that woke up next.
     */
    @Test
    void aDueInstantThatIsNotInTheFutureIsRefused() throws Exception {
        for (String due : new String[] {Instant.now().minusSeconds(60).toString(), Instant.now().toString()}) {
            mvc.perform(put("/v1/groups/{groupId}/schedule", GROUP)
                            .header("Authorization", "Bearer " + alice)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"everyDays\":30,\"nextDueAt\":\"" + due + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("api.schedule_due_in_the_past"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_schedules", Integer.class)).isZero();
    }

    /** A body that does not say both things is refused as incomplete rather than as a cadence of zero. */
    @Test
    void aBodyThatIsMissingAFieldIsRefusedAsIncomplete() throws Exception {
        mvc.perform(put("/v1/groups/{groupId}/schedule", GROUP)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nextDueAt\":\"" + Instant.now().plusSeconds(3_600) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));

        mvc.perform(put("/v1/groups/{groupId}/schedule", GROUP)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"everyDays\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("api.request_incomplete"));
    }

    /** No schedule is a refusal of its own, for the read and the clear alike - and a second clear says the same thing. */
    @Test
    void aGroupWithNoScheduleSaysSo() throws Exception {
        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_schedule"));

        setSchedule(30, Instant.now().plusSeconds(3_600));

        mvc.perform(delete("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_schedule"));

        mvc.perform(delete("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_schedule"));
    }

    /**
     * A stranger may do none of the three, and the read answers the group's own refusal rather than the schedule's.
     *
     * <p>{@code no_such_group} for a stranger is the ordering ADR 0049 and 0054 both require: telling "no such group"
     * from "you may not read it" apart would let somebody holding a guessed id learn which groups are real - and when a
     * group closes its books is a fact about its books.
     */
    @Test
    void onlyAMemberMaySetReadOrClearIt() throws Exception {
        setSchedule(30, Instant.now().plusSeconds(3_600));

        String stranger = signedIn("+919899999998");

        mvc.perform(put("/v1/groups/{groupId}/schedule", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("api.not_signed_in"));

        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        mvc.perform(delete("/v1/groups/{groupId}/schedule", GROUP)
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("api.no_such_group"));

        // Still there, and still the one this member set.
        mvc.perform(get("/v1/groups/{groupId}/schedule", GROUP).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.everyDays").value(30));
    }

    /** A group that exists only as rows can state a cadence, which is the state every read test here uses (ADR 0043). */
    @Test
    void aGroupWithNoRowOfItsOwnCanStateOne() throws Exception {
        UUID bare = UUID.randomUUID();

        memberRow(bare, ALICE, "Alice");

        setSchedule(bare, 7, Instant.now().plusSeconds(3_600));

        mvc.perform(get("/v1/groups/{groupId}/schedule", bare).header("Authorization", "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.everyDays").value(7));
    }

    private void setSchedule(int everyDays, Instant due) throws Exception {
        setSchedule(GROUP, everyDays, due);
    }

    /**
     * An instant a schedule can be due at, at second precision.
     *
     * <p>Truncated deliberately: {@code Instant.now()} carries nanoseconds and Postgres keeps microseconds, so a test
     * that compared the two would be asserting that a database stores more than it does. The other tests in this module
     * avoid the problem by writing literals; these compare a value that came from a clock, so they round it first.
     */
    private static Instant due() {
        return Instant.now().plusSeconds(3_600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    private void setSchedule(UUID group, int everyDays, Instant due) throws Exception {
        mvc.perform(put("/v1/groups/{groupId}/schedule", group)
                        .header("Authorization", "Bearer " + alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"everyDays\":" + everyDays + ",\"nextDueAt\":\"" + due + "\"}"))
                .andExpect(status().isOk());
    }
}