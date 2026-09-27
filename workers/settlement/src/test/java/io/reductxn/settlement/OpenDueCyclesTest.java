package io.reductxn.settlement;

import io.reductxn.settlement.port.in.OpenDueCycles;
import io.reductxn.settlement.port.in.OpenedCycles;
import io.reductxn.settlement.port.out.GroupSchedules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schedules a group states, honoured by the worker (ADR 0057).
 *
 * <p><strong>What this class pins is the pair of guarantees that make a cadence safe.</strong> A due schedule opens
 * exactly one cycle, at the instant the group stated rather than at the moment the worker noticed; and the schedule
 * moves on by exactly one interval, moves only once even if two passes read the same row, and stays where it is when
 * another pass got there first. Each of those is an assertion about the *row*, not about a call's return value: the row
 * is what another worker would see, and the report is only what this pass believes.
 *
 * <p>It runs against the real schema, because the conditional advance is a SQL statement - the expectation is in the
 * {@code WHERE} - and a fake store would agree with whatever the test expected.
 */
@SpringBootTest(classes = SettlementWorkerApplication.class)
class OpenDueCyclesTest extends WorkerDatabaseTest {

    /** The instant a schedule's first closure is due at, comfortably in the past so no test depends on the clock. */
    private static final Instant DUE_AT = Instant.parse("2026-09-01T00:00:00Z");

    /** "Now" for a pass: later than the due instant above, so a schedule can be overdue rather than future. */
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");

    @Autowired
    private OpenDueCycles openDueCycles;

    /** The worker's own read of the table, as a deployment's worker has it. */
    @Autowired
    private GroupSchedules schedules;

    @BeforeEach
    void clearSchedules() {
        jdbc.update("DELETE FROM group_schedules");
    }

    /** A member's schedule closes the books without anybody asking, and moves on by its interval. */
    @Test
    void aDueScheduleIsOpenedAndAdvanced() {
        UUID group = UUID.randomUUID();
        schedule(group, 7, DUE_AT);

        OpenedCycles report = openDueCycles.openDue(NOW, 10);

        assertThat(report.outcomes()).hasSize(1);
        assertThat(report.opened()).isOne();

        OpenedCycles.Outcome outcome = report.outcomes().getFirst();

        assertThat(outcome.opened()).as("this pass opened it").isTrue();
        assertThat(outcome.advanced()).as("and this pass moved the schedule on").isTrue();
        assertThat(outcome.dueAt()).as("the cycle is dated by the schedule, not by the pass").isEqualTo(DUE_AT);
        assertThat(outcome.nextDueAt()).isEqualTo(DUE_AT.plus(Duration.ofDays(7)));

        assertThat(cycleAt(group, DUE_AT)).as("the cycle exists at the instant the group stated").isTrue();

        assertThat(nextDueAt(group))
                .as("and the row now says the next closure is a week later")
                .isEqualTo(DUE_AT.plus(Duration.ofDays(7)));
    }

    /** A schedule that is not due yet is not touched, and opens nothing. */
    @Test
    void aScheduleThatIsNotDueYetIsLeftAlone() {
        UUID group = UUID.randomUUID();
        Instant future = NOW.plus(Duration.ofDays(1));
        schedule(group, 7, future);

        OpenedCycles report = openDueCycles.openDue(NOW, 10);

        assertThat(report.outcomes()).isEmpty();
        assertThat(nextDueAt(group)).as("the row is exactly where it was").isEqualTo(future);
        assertThat(cycleAt(group, future)).isFalse();
    }

    /** A second pass over the same rows opens nothing twice - which is the whole reason the advance is conditional. */
    @Test
    void aSecondPassOpensNothingTwice() {
        UUID group = UUID.randomUUID();

        // Due a moment ago, with a long interval, so the advance lands in the future and the next pass finds nothing.
        // (A group that is *weeks* behind is the case the next test covers, and there the second pass legitimately finds
        // the next missed period: draining one period per pass is the documented behaviour, not a duplicate.)
        Instant justOverdue = NOW.minus(Duration.ofHours(1));
        schedule(group, 30, justOverdue);

        OpenedCycles first = openDueCycles.openDue(NOW, 10);
        OpenedCycles second = openDueCycles.openDue(NOW, 10);

        assertThat(first.outcomes()).hasSize(1);
        assertThat(second.outcomes()).as("the row moved out of the past, so nothing is due").isEmpty();
        assertThat(nextDueAt(group)).isEqualTo(justOverdue.plus(Duration.ofDays(30)));
        assertThat(countCycles(group)).as("one period, one cycle").isEqualTo(1);
    }

    /**
     * Two passes that read the same row cannot both move it: one advances it, the other reports that it did not.
     *
     * <p>The race the conditional update exists for, driven directly rather than with two threads: the store is asked to
     * advance from an expectation that is no longer true, which is exactly what the loser of that race asks for.
     */
    @Test
    void aStaleAdvanceMovesNothing() {
        UUID group = UUID.randomUUID();
        schedule(group, 30, DUE_AT);

        assertThat(schedules.advance(group, DUE_AT, DUE_AT.plus(Duration.ofDays(30))))
                .as("the first caller moves it").isTrue();

        assertThat(schedules.advance(group, DUE_AT, DUE_AT.plus(Duration.ofDays(30))))
                .as("the second advances from an instant the row no longer holds").isFalse();

        assertThat(nextDueAt(group)).as("so the row moved once, not twice").isEqualTo(DUE_AT.plus(Duration.ofDays(30)));
    }

    /** A worker that was down finds the periods it missed, oldest first, and drains them one period per pass. */
    @Test
    void anOverdueScheduleDrainsOnePeriodPerPass() {
        UUID group = UUID.randomUUID();
        schedule(group, 1, DUE_AT);

        OpenedCycles first = openDueCycles.openDue(NOW, 10);

        assertThat(first.outcomes()).hasSize(1);
        assertThat(first.outcomes().getFirst().dueAt()).as("the oldest period, not the newest").isEqualTo(DUE_AT);
        assertThat(nextDueAt(group)).isEqualTo(DUE_AT.plus(Duration.ofDays(1)));

        // The next pass finds the next period, because a row still in the past is still due.
        OpenedCycles second = openDueCycles.openDue(NOW, 10);

        assertThat(second.outcomes()).hasSize(1);
        assertThat(second.outcomes().getFirst().dueAt()).isEqualTo(DUE_AT.plus(Duration.ofDays(1)));
    }

    /**
     * The batch bounds how many groups one pass touches, oldest first.
     *
     * <p>A year-long interval on each of the three, so a pass that touches a row moves it out of the past and the next
     * pass has exactly the groups it did not reach. (With a short interval the arithmetic would mix the two behaviours -
     * draining a backlog and bounding a batch - and the assertion would be about neither.)
     */
    @Test
    void theBatchBoundsOnePass() {
        for (int index = 0; index < 3; index++) {
            schedule(UUID.randomUUID(), 366, DUE_AT.plus(Duration.ofDays(index)));
        }

        List<OpenedCycles.Outcome> firstPass = openDueCycles.openDue(NOW, 2).outcomes();

        assertThat(firstPass).as("two groups per pass, so three need two passes").hasSize(2);
        assertThat(openDueCycles.openDue(NOW, 2).outcomes()).as("the last group, on the next pass").hasSize(1);

        assertThat(firstPass.getFirst().dueAt())
                .as("oldest first, so a backlog drains in the order the periods happened")
                .isEqualTo(DUE_AT);
    }

    private void schedule(UUID group, int everyDays, Instant nextDueAt) {
        jdbc.update("INSERT INTO group_schedules (group_id, every_days, next_due_at, set_at) VALUES (?, ?, ?, ?)",
                group, everyDays, OffsetDateTime.ofInstant(nextDueAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(nextDueAt.minus(Duration.ofDays(30)), ZoneOffset.UTC));
    }

    private Instant nextDueAt(UUID group) {
        return jdbc.queryForObject(
                "SELECT next_due_at FROM group_schedules WHERE group_id = ?", OffsetDateTime.class, group).toInstant();
    }

    private boolean cycleAt(UUID group, Instant asOf) {
        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM settlement_cycles WHERE group_id = ? AND as_of = ?", Integer.class, group,
                OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));

        return found != null && found > 0;
    }

    private int countCycles(UUID group) {
        return jdbc.queryForObject("SELECT count(*) FROM settlement_cycles WHERE group_id = ?", Integer.class, group);
    }
}