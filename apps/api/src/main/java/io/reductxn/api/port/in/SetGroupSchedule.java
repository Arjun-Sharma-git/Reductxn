package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: state when a group's books close, if the caller is in the group (ADR 0057).
 *
 * <p><strong>It is the write {@code OpenCycle} said it was not deciding.</strong> That port's own words: "Nothing here
 * decides that a cycle has come due. That is a group's schedule, a group's property, and there is no group schedule in
 * this build." This is the property, and setting it is a member's act for the same reason opening a cycle by hand is: a
 * schedule is a standing instruction to close the group's books, which is an act within the group (ADR 0049, 0056).
 *
 * <p>Three conditions can refuse it, and they are three different sentences: the caller is not a member
 * ({@code no_such_group}), the interval is outside the range the contract declares
 * ({@code schedule_every_days_out_of_range}), and the due instant is not in the future
 * ({@code schedule_due_in_the_past}). The third is what keeps this command and {@code POST /groups/{groupId}/cycles}
 * from meaning the same thing once.
 */
public interface SetGroupSchedule {

    /**
     * Writes the group's schedule, replacing whatever it had.
     *
     * @return the schedule as written, or the reason it was refused
     */
    Result<CycleSchedule> set(UUID callerId, NewSchedule schedule);
}