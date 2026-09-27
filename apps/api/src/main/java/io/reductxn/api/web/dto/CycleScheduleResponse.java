package io.reductxn.api.web.dto;

import java.time.Instant;

/**
 * The contract's `CycleSchedule` schema: when a group's books close by themselves (ADR 0057).
 *
 * <p>Three fields and no `groupId`: the caller named the group in the path, and a response that repeated it would be a
 * second place for the same fact - the shape {@code InviteResponse} gives the same reason for.
 *
 * <p>{@code nextDueAt} and {@code setAt} are both on the wire because they answer different questions: when the books
 * close next, and whether the statement is current. A client that derived one from the other and `everyDays` would be a
 * second implementation of the cadence's arithmetic, and the first time a worker missed a period the two would disagree.
 */
public record CycleScheduleResponse(int everyDays, Instant nextDueAt, Instant setAt) {

    /** The schedule, as the document describes it. */
    public static CycleScheduleResponse of(io.reductxn.api.port.in.CycleSchedule schedule) {
        return new CycleScheduleResponse(schedule.everyDays(), schedule.nextDueAt(), schedule.setAt());
    }
}