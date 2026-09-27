package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.util.UUID;

/**
 * The use case: when a group's books close next, if the caller is in the group (ADR 0057).
 *
 * <p><strong>A member reads it, which is a decision rather than an oversight.</strong> A schedule says when a group's
 * obligations get settled, so it is a fact about the group's books - and since ADR 0054 every read of those is answered
 * to a member. The one exception in this API is a share link ({@code GET /shared/{code}}), and a link shows *what is
 * owed* rather than when the group acts next: adding a schedule to it would be a disclosure the group did not make by
 * handing the link out.
 *
 * <p>An absent schedule is {@code api.no_such_schedule}, not a group with an empty one: a screen has to tell "this group
 * has not said" from "they close every thirty days", and a null instant in a successful answer invites a client to
 * misread the first as the second.
 */
public interface ReadGroupSchedule {

    /**
     * The group's schedule.
     *
     * @return the schedule, or {@code no_such_group} / {@code no_such_schedule}
     */
    Result<CycleSchedule> read(UUID callerId, UUID groupId);
}