# ADR 0057 - A group states when its books close, and the worker honours it

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `V110__group_schedules.sql`, the three `/groups/{groupId}/schedule` operations and the
  `CycleSchedule`/`CycleScheduleRequest` schemas, `GroupSchedules` (a port-out in each deployable), `CycleSchedule`,
  `NewSchedule`, `SetGroupSchedule`/`ReadGroupSchedule`/`ClearGroupSchedule` and their services, `ScheduleRefusals`,
  `JooqGroupSchedules`, `GroupScheduleController`, `OpenDueCycles`/`OpenedCycles`/`OpenDueCyclesService`,
  `JooqDueSchedules`, `ScheduledCycleOpener`, `GroupScheduleApiTest` (8 tests), `OpenDueCyclesTest` (6 tests),
  `entities/schedule`, the `schedule` feature and route, the nav entry, the e2e stubs and three specs
- **Aligns with:** ADR 0018 (the worker drives what it owns), ADR 0032 (the worker computes what a cycle becomes),
  ADR 0049/0054 (a member is who may write to and read a group's books), ADR 0043 (a group need not have a `groups` row)
- **Related:** ADR 0041 (the manual operation this does not replace), ADR 0021 (one description of a thing),
  ADR 0056 (who may state one); RULE B15, B17, B21, C1, C3, F1, F4, F8, F10, I3

## Context

`OpenCycle` in the settlement worker said the gap out loud, and this ADR is the answer to it:

> **Nothing here decides that a cycle has come due.** That is a group's schedule, a group's property, and there is no
> group schedule in this build (the plan records it as an open question and this port forecloses none of the answers).

The pipeline was complete behind that sentence and empty in front of it: a cycle could be opened (by an operation a
member called), a run could be computed for an open cycle, and a computed run could be driven to a rail. So every period
in every group was closed because somebody remembered to close it - a product where the "settlement" feature only happens
if a person asks, every time, for ever.

Two things had to be decided, and both are about *when*:

1. **What a group states.** A cadence, or a date in the calendar.

## Decision

**A group states a cadence as an interval in whole days and the instant its next cycle is due; the worker opens a cycle
at exactly that instant and advances the row by the interval.**

- **`group_schedules(group_id, every_days, next_due_at, set_at)`, one row per group.** A second schedule for one group
  would be two answers to "when are your books closed" and the reads that answer one would have to pick; changing a
  schedule is therefore the same operation as setting it, which is what `PUT` on a singular path means. There is no `id`
  column: nothing addresses a schedule but its group.
- **It is a group's property, not a run's.** A cycle is already `(group, as_of)`, and a schedule is the standing
  instruction that decides which instants there will be - so it lives beside the group and not on any cycle, and nothing
  about a cycle's *content* is in it (ADR 0038: what is recorded before an instant is what that cycle settles).
- **The instant is stored, not derived.** `next_due_at` is what the worker acts on and `every_days` is what it adds
  afterwards; a reader that computed one from the other and `set_at` would be a second implementation of the cadence's
  arithmetic, and the first period a worker missed would make the two disagree - which is exactly the state a stored
  instant reports honestly.
- **The cycle is opened at the group's instant, not at the worker's.** A worker that was down when a period fell due
  opens it late but dated correctly, and the run computed for it resolves the obligations at that instant (ADR 0032).
  This is the difference between "the books closed on the 1st, late" and "the books closed whenever the worker came
  back", and only the first is a statement a group made.
- **Whole days between 1 and 366, and no calendar anchor.** Zero is refused rather than looped on - "every zero days"
  is a bus, not a cadence - and more than a year is not an interval anybody means. There is deliberately **no time zone
  and no day-of-month**: "the first of the month" needs both (whose midnight, and month lengths that differ), and that is
  a decision nobody has made, so it is recorded as the next thing a group would ask for rather than guessed at here.
- **A due instant that is not in the future is refused** (`422 api.schedule_due_in_the_past`). Otherwise stating a
  schedule would be a second way of closing the books immediately, which the manual operation already is; and a past
  instant in a date field is nearly always a typo rather than an intention.
- **A member states, reads and clears it**, and a stranger gets `no_such_group` for all three (ADR 0048, 0049, 0054):
  a cadence is a standing instruction to close the group's books, and when a group closes its books is a fact about its
  books.

## Alternatives rejected

- **A cron expression.** The most flexible form and the worst to store: it encodes a calendar, a zone and a day-of-week
  rule, and it would let a group state something nobody can reproduce - "every 30 days" is checkable by hand and
  `17 3 * * 1` is not. It also invites the two things this ADR refused together: a zone (whose?) and a month rule.
- **A date in the month.** What a group actually wants in production, and refused *for now* rather than as wrong: it
  needs a zone and a rule for the 31st, and both are decisions with real consequences (a period that never happens in
  February). Recorded as the next step.
- **The API opening cycles on a request** (no worker). Then a group's periods depend on somebody calling the API on the
  right day, which is the state this slice exists to leave.
- **A `groups` column rather than a table.** A schedule is `NOT NULL`-able here and a group need not have a `groups` row
  at all (ADR 0043), so a column would either make a schedule impossible for exactly the groups that method cannot
  address or make the column nullable and the absence ambiguous.
- **Storing the next due instant only, as a queue** (a row per due closure rather than a cadence). It would make a
  catch-up explicit and unbounded in size, and the group's statement - "every 30 days" - would be lost in the rows.
- **`every_days` as a duration** ("PT720H"). The same arithmetic, and a value no screen shows or a person checks.
- **Advancing the schedule to the next *future* instant when a pass catches up** (skipping the missed periods). It looks
  tidy and it silently discards periods a group asked for: a cycle that was due on the 1st is a period, and a run
  computed for it resolves the obligations recorded inside it.
- **Accepting a due instant in the past and opening at once.** It collapses two operations into one meaning, and it would
  make "state a cadence" the way to close the books now - which the plan for a period already is, with its own refusals.
- **Clearing returning `204` whether or not there was a schedule.** Cheaper for a client and a lie: it reports that a
  removal happened when nothing did.
- **A schedule read open to a share link** (`GET /shared/{code}` including it). A link shows what is owed; when the group
  acts next is a different disclosure, and not one a group makes by handing out a link.

## Consequences

- **The pipeline has a front now.** A group that states a cadence has its books closed, its plan computed and its run
  driven without anybody remembering anything - and the operation a member could always call is still there for the
  period that has to close today.
- **Two deployables share one new table, each with its own port** (RULE B15): the API writes it, the worker reads and
  advances it, and neither imports the other's class. The tests follow the same split - `GroupScheduleApiTest` asserts
  what a member's request writes, `OpenDueCyclesTest` asserts what a due row becomes, and the table is the seam.
- **The browser suite proved something the API tests could not, once again**: the client's write was
  double-stringified (`request` stringifies its `body`, and the mutation stringified it first), so the schedule the
  server received had no `everyDays` at all. The API's tests pass a body as a string by construction and would have
  accepted it; the form test read the rendered card and caught it. Recorded because it is the second time this slice
  series has found a defect at the seam between a passing API suite and a working screen.
- **`DatabaseTest` and `WorkerDatabaseTest` both had to learn about the new table**, and for the same reason: a row left
  behind makes a later test see a group whose books close by themselves. The new cadence is switched off in the worker's
  test properties alongside the other two, because a tick mid-test would race the test's own pass.
- **Numbers**: 241 API tests (was 233), 6 worker tests (was 0 for this pipeline stage), 65 browser tests (was 62), 69
  client unit tests, 440 catalog keys, 50 server error codes.

- **No schedule is `404 api.no_such_schedule`**, distinct from `no_such_group`, because a screen has to tell "this group
  has not said" from "you are looking at somebody else's group" - and a null instant in a successful answer invites a
  client to misread the first as the second. Clearing says the same, for the same reason: a `204` for "removed it" and
  for "there was nothing" would tell a client its request did something it did not.
- **The worker has a third tick.** `ScheduledCycleOpener` opens cycles from due schedules, `ScheduledRunComputer`
  computes runs for cycles that have none, and `ScheduledRunDriver` settles what was computed - three rates of work with
  three settings, because one pass would let a rail having a bad minute stop cycles being opened at all.
- **Exactly once in practice, by two statements.** `dueBefore` is a read that two passes can both see; `advance` is a
  conditional update that only one of them can win, and the cycle's own uniqueness on `(group_id, as_of)` settles the
  other half. Neither is a lock and neither needs one.
- **A backlog drains one period per pass.** A worker down for a week with a daily cadence finds seven due periods, opens
  the oldest and advances the row by one day, and the next pass finds the next - a trickle that reads as a catch-up
  rather than a burst that reads as an incident. The batch size bounds *groups per pass*, not how far behind a group is.
- **The client can state one.** `/groups/$groupId/schedule` reads what the worker will do, states a cadence in a form of
  two fields, and stops it; the group's nav gains a fifth entry.

### What is deliberately not decided here

- **Calendar-anchored schedules** ("the first of the month", "every Monday"): needs a time zone column, a rule for month
  lengths, and a decision about whose midnight. The next thing a group would ask for, and a migration rather than a
  guess.
- **A schedule per currency.** A cross-currency group closes one set of books at one instant today (the cycle is
  `(group, as_of)`), so a cadence per currency would need cycles that are not that.
- **What happens to a period that is already closed.** The worker opens a cycle at an instant; a second attempt at the
  same instant is the same cycle (`on conflict do nothing`), which is what makes a retry ordinary. Nothing here reopens or
  merges periods.
- **Revoking a link, a window a caller chooses, a third grant** - still open from ADR 0055, and untouched by this.

2. **Who acts on it.** A worker, on a tick of its own, or the API on a request.

The second is settled by ADR 0018: the worker drives what it owns, and "when does a period close" is the front of the
pipeline the worker already runs. The first is this ADR's decision.
