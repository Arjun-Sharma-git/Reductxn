package io.reductxn.api.application.schedules;

import io.reductxn.api.port.in.ClearGroupSchedule;
import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ScheduleRefusals;
import io.reductxn.api.port.out.GroupSchedules;
import io.reductxn.api.port.out.Groups;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * The use case: stop a group's cadence, so nothing closes its books by itself (ADR 0057).
 *
 * <p><strong>The absence is a refusal rather than a success.</strong> Answering `204` to both "removed it" and "there
 * was nothing" would tell a client its request did something it did not, and a screen that shows "the cadence is
 * stopped" after a request that changed nothing is the kind of small lie this repository keeps removing. So the row's
 * absence is {@code api.no_such_schedule}, the same code the read gives, because it is the same condition.
 *
 * <p>The membership check comes first for the same reason it does everywhere else: a stranger must not be able to ask
 * whether a group has a cadence.
 *
 * <p>Nothing else is touched, deliberately: a cycle the worker has already opened stays open, and the run computed for
 * it settles as it would have. A schedule decides when a closure happens, never what it contains.
 */
@Service
public class ClearGroupScheduleService implements ClearGroupSchedule {

    private final GroupSchedules schedules;
    private final Groups groups;

    public ClearGroupScheduleService(GroupSchedules schedules, Groups groups) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.groups = Objects.requireNonNull(groups, "groups");
    }

    @Override
    public Result<Void> clear(UUID callerId, UUID groupId) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");

        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        if (!schedules.clear(groupId)) {
            return Result.fail(new ScheduleRefusals.NoSuchSchedule(groupId));
        }

        return Result.ok(null);
    }
}