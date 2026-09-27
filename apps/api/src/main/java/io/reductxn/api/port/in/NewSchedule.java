package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A group stating when its books close: the group, the interval, and the instant the next cycle is due (ADR 0057).
 *
 * <p><strong>There is no "starts at" as distinct from "due next".</strong> They are the same instant, and a schedule
 * whose first closure could be on a different date from its next one would need a rule for what happens in between -
 * which is a calendar decision, not a field. The service stamps {@code setAt} from the boundary's clock (RULE B21), so a
 * test can pin the instant and get a reproducible row.
 *
 * <p><strong>The instant must be in the future, and that refusal is a decision.</strong> Setting a schedule with a due
 * instant that has passed would make this command a way of closing the books immediately, which is what
 * {@code POST /groups/{groupId}/cycles} exists for; two operations that can do one thing is how a group's periods stop
 * being explained by its schedule. "Future" is judged against {@code setAt}, which is the instant the boundary read from
 * its clock - the same value the row is stamped with, so the two cannot disagree about when this was decided.
 *
 * @param groupId   the group the cadence belongs to
 * @param everyDays the interval between closures, in whole days, between 1 and 366
 * @param nextDueAt the instant the next cycle is due; must be in the future
 * @param setAt     the instant the boundary read, supplied rather than read from a clock (RULE B21): the row's stamp,
 *                  and the "now" the due instant is judged against
 */
public record NewSchedule(UUID groupId, int everyDays, Instant nextDueAt, Instant setAt) {

    public NewSchedule {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nextDueAt, "nextDueAt");
        Objects.requireNonNull(setAt, "setAt");
    }
}