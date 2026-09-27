package io.reductxn.api.application.schedules;

import io.reductxn.api.port.in.CycleSchedule;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadGroupSchedule;
import io.reductxn.api.port.in.ScheduleRefusals;
import io.reductxn.api.port.out.GroupSchedules;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The use case: when a group's books close next, if the caller is in the group (ADR 0057).
 *
 * <p><strong>Two conditions, in the order that says the least to a stranger.</strong> Membership first, so somebody not
 * in the group cannot tell "there is no schedule" from "you may not read it" - one answer for both, which is ADR 0049's
 * ordering and ADR 0054's rule for every read of a group's books. Then the row: absent means
 * {@code api.no_such_schedule}, which is a different fact from a group that does not exist, and one a member can act on
 * ("set a schedule" rather than "you are looking at somebody else's group").
 *
 * <p><strong>It reads one row and computes nothing.</strong> Notably it does not derive the due instant from the stamp
 * and the interval: the row is what the worker will act on, and a reader computing it differently would be a second
 * implementation of the cadence's arithmetic - which would disagree the moment a worker missed a period because it was
 * down.
 *
 * <p>{@code @Transactional(readOnly = true)} because it is one statement: no isolation is claimed that a single query
 * does not have.
 */
@Service
public class ReadGroupScheduleService implements ReadGroupSchedule {

    private final GroupSchedules schedules;
    private final Groups groups;

    public ReadGroupScheduleService(GroupSchedules schedules, Groups groups) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<CycleSchedule> read(UUID callerId, UUID groupId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");

        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        Optional<CycleSchedule> schedule = schedules.read(groupId);

        return schedule
                .map(Result::ok)
                .orElseGet(() -> Result.fail(new ScheduleRefusals.NoSuchSchedule(groupId)));
    }
}