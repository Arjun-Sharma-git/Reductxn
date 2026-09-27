package io.reductxn.api.application.schedules;

import io.reductxn.api.port.in.CycleSchedule;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.NewSchedule;
import io.reductxn.api.port.in.ScheduleRefusals;
import io.reductxn.api.port.in.SetGroupSchedule;
import io.reductxn.api.port.out.GroupSchedules;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: state when a group's books close, if the caller is in the group (ADR 0057).
 *
 * <p><strong>The order of the three questions is the decision, not the arithmetic.</strong> Membership first, so a
 * stranger learns nothing about a group (ADR 0049's ordering, applied here): a refusal that answered "the date you gave
 * has passed" to somebody who is not in the group would be a refusal that had already said the group exists. Then the
 * contract's range, which is a property of the request alone. Then the instant, which is the one condition that needs the
 * clock - and it needs it from the boundary rather than from here, because a test that pins "now" has to be able to.
 *
 * <p><strong>What is deliberately not checked: whether the instant is a business day, whether one closure per interval
 * is achievable, or whether the group has anything recorded to settle.</strong> The first is a calendar decision this
 * build has not made (the plan records calendar-anchored schedules as the next thing a group would ask for); the second
 * is the worker's to discover, not this operation's to prevent; and the third is the same absence the manual operation
 * has, for the same reason: a commitment recorded later can carry an instant inside a period that is empty today.
 *
 * <p>No {@code @Transactional}: one upsert, whose primary key is what settles every race it can have - two members
 * setting a schedule at once leave one of them in force, which is what "a group has one schedule" means.
 */
@Service
public class SetGroupScheduleService implements SetGroupSchedule {

    /** The contract's range, stated once here and in the table's CHECK. */
    static final int MIN_EVERY_DAYS = 1;
    static final int MAX_EVERY_DAYS = 366;

    private final GroupSchedules schedules;
    private final Groups groups;

    public SetGroupScheduleService(GroupSchedules schedules, Groups groups) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<CycleSchedule> set(UUID callerId, NewSchedule schedule) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(schedule, "schedule");

        if (!groups.isMember(schedule.groupId(), callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(schedule.groupId()));
        }

        if (schedule.everyDays() < MIN_EVERY_DAYS || schedule.everyDays() > MAX_EVERY_DAYS) {
            return Result.fail(new ScheduleRefusals.EveryDaysOutOfRange(
                    schedule.everyDays(), MIN_EVERY_DAYS, MAX_EVERY_DAYS));
        }

        Instant now = schedule.setAt();

        // Half-open, like every window in this codebase: a due instant exactly at "now" is already due, and the refusal
        // says so rather than letting the worker open a cycle in the pass it woke up on.
        if (!schedule.nextDueAt().isAfter(now)) {
            return Result.fail(new ScheduleRefusals.DueInThePast(schedule.nextDueAt(), now));
        }

        return Result.ok(schedules.put(schedule.groupId(), schedule.everyDays(), schedule.nextDueAt(), now));
    }
}