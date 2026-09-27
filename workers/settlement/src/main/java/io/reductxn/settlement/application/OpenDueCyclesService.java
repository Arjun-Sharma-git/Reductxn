package io.reductxn.settlement.application;

import io.reductxn.settlement.port.in.OpenCycle;
import io.reductxn.settlement.port.in.OpenDueCycles;
import io.reductxn.settlement.port.in.OpenedCycles;
import io.reductxn.settlement.port.in.RunRequest;
import io.reductxn.settlement.port.out.GroupSchedules;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The use case: opens the cycles a group's schedule has brought due, and moves each schedule on (ADR 0057).
 *
 * <p><strong>Three statements per schedule, and the order is the guarantee.</strong> Read what is due; open the cycle at
 * the schedule's own instant; advance the schedule by its interval - conditionally, so only the pass that read the row
 * still moves it. The alternative order (advance first, then open) would lose a period if the process died in between,
 * and the alternative to the conditional advance would lose one whenever two passes read the same row.
 *
 * <p><strong>A backlog drains one period per pass, deliberately.</strong> A worker that was down for a week with a daily
 * cadence finds seven due periods; this pass opens the oldest and advances the row by a day, and the next pass finds the
 * one after it. Catching up in one pass was rejected because it would open several cycles at instants that are all in
 * the past and then compute a run for each - a burst of work that reads as an incident, where a trickle is exactly what
 * happened. The batch size bounds how many *groups* one pass touches, not how far behind each of them is.
 *
 * <p><strong>The interval is added as a duration rather than a calendar step, and this is where that shows.</strong> The
 * next due instant is the old one plus `everyDays` days, in UTC, whatever month that lands in - which is why the contract
 * refuses to describe a schedule as "the first of the month" (ADR 0057's own note, and the plan's open item).
 *
 * <p>Nothing here catches: a fault surfaces to the caller, which is the component that decides whether the cadence's tick
 * survives it.
 */
@Service
public class OpenDueCyclesService implements OpenDueCycles {

    private final GroupSchedules schedules;
    private final OpenCycle openCycle;

    public OpenDueCyclesService(GroupSchedules schedules, OpenCycle openCycle) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.openCycle = Objects.requireNonNull(openCycle, "openCycle");
    }

    @Override
    public OpenedCycles openDue(Instant dueBefore, int batchSize) {
        Objects.requireNonNull(dueBefore, "dueBefore");

        List<OpenedCycles.Outcome> outcomes = new ArrayList<>();

        for (GroupSchedules.DueSchedule due : schedules.dueBefore(dueBefore, batchSize)) {
            // The cycle, at the instant the group stated. `OpenCycle` inserts with `on conflict do nothing` on
            // (group_id, as_of), so a cycle that is already open is the ordinary answer rather than an error - which is
            // what makes this pass safe to run twice, or from two workers at once.
            boolean opened = openCycle.open(new RunRequest(due.groupId(), due.nextDueAt()));

            Instant nextDueAt = due.nextDueAt().plus(Duration.ofDays(due.everyDays()));
            boolean advanced = schedules.advance(due.groupId(), due.nextDueAt(), nextDueAt);

            outcomes.add(new OpenedCycles.Outcome(due.groupId(), due.nextDueAt(), nextDueAt, opened, advanced));
        }

        return new OpenedCycles(outcomes);
    }
}