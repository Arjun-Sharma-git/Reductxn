package io.reductxn.api.port.in;

import java.time.Instant;
import java.util.Objects;

/**
 * A group's schedule: when its next cycle is due, how often after that, and when the group last said so (ADR 0057).
 *
 * <p><strong>It is a group's property rather than a run's, and that is why it is its own record.</strong> A cycle is
 * already a group and an instant ({@code settlement_cycles} is unique on the pair), and a schedule is the standing
 * instruction that decides which instants there will be - so the fields it needs are the interval, the next instant, and
 * a stamp saying how current the statement is. Nothing about a cycle's *content* is here: what is recorded before an
 * instant is what that cycle settles (ADR 0038), and the plan resolves it when the run is computed.
 *
 * <p>{@code nextDueAt} is stored rather than derived from {@code setAt} and {@code everyDays}, and the difference
 * matters: the row is what the worker acts on, and the interval is what it will add afterwards. A reader that computed
 * one from the other two would be a second implementation of the cadence's arithmetic - and the moment a worker misses a
 * period (it was down), the two would disagree.
 *
 * @param everyDays  the interval between closures, in whole days, between 1 and 366
 * @param nextDueAt  the instant the next cycle is due
 * @param setAt      when the schedule was last set or changed
 */
public record CycleSchedule(int everyDays, Instant nextDueAt, Instant setAt) {

    public CycleSchedule {
        Objects.requireNonNull(nextDueAt, "nextDueAt");
        Objects.requireNonNull(setAt, "setAt");
    }
}