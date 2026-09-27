-- A group's schedule: when its next cycle is due, and how often after that (ADR 0057).
--
-- Owned by apps/api, continuing its `V10x` range (RULE B17). What it adds is the one thing `OpenCycle` said out loud it
-- did not decide:
--
--   "**Nothing here decides that a cycle has come due.** That is a group's schedule, a group's property, and there is no
--   group schedule in this build."
--
-- Until now a cycle was opened by a person: `POST /groups/{groupId}/cycles` records that a group's books close at an
-- instant, the worker then computes a run for every cycle that has none, and the driver settles it. Every group in a
-- deployment therefore settled when somebody remembered, which is the gap the plan has carried since ADR 0032 as
-- "nothing decides when a cycle is due".
--
-- **The key is `group_id`, because a group has one schedule rather than a list of them.** A second schedule for one group
-- would be two answers to "when are your books closed", and the operations that read one would have to pick. Changing a
-- schedule is an upsert on this row, which is exactly what a `PUT` means - and it is also why there is no `id` column: a
-- surrogate key would be a second identifier for one thing (the shape ADR 0040 removed from `Member`), and nothing could
-- address it, since the contract's operations name the group.
--
-- **No foreign key to a `groups` table**, for the reason V104 and V105 both give: there need not be a `groups` row for a
-- group to exist (ADR 0043). A schedule is a property of a group id, and a group that exists only as rows can have one.
--
-- **No `time_zone` column, and that is the biggest decision in this file.** A schedule of "every 30 days" is an interval
-- between instants, so it needs no zone: the worker adds a fixed duration and the arithmetic is the same in every place
-- a reader might be. "The first of the month" would need one - whose month, at whose midnight - and that is a calendar
-- decision with its own questions (month lengths, daylight saving, a group that moves), so the column does not exist and
-- the plan records calendar-anchored schedules as the next thing a group would ask for. Adding the column later is a
-- migration; guessing the zone now would be a value nothing chose.
--
-- **`every_days` has a floor of one and a ceiling of a year.** Zero would open a cycle on every pass, which is not a
-- schedule but a bus (and `settlement_cycles` is unique on `(group_id, as_of)`, so each pass would open a new one at a
-- new instant). Above a year is not a cadence anybody means; a group that wants a one-off closes its books by hand with
-- the operation that already exists.

CREATE TABLE group_schedules (
    group_id    uuid        PRIMARY KEY,

    -- The interval between one closure and the next, in days. Whole days rather than an arbitrary duration, because a
    -- cadence is something a group states in days and a column that stored "PT36H" would be a value no screen shows.
    every_days  integer     NOT NULL,

    -- When the next cycle is due, as an instant. The worker opens a cycle at exactly this instant rather than at the
    -- moment it noticed, so a group's periods are the ones it asked for even if the worker was down when they fell due.
    next_due_at timestamptz NOT NULL,

    -- When the schedule was last set or changed. A reader wants to know whether the statement above it is current, and
    -- nothing else in this row can say.
    set_at      timestamptz NOT NULL,

    CONSTRAINT group_schedules_every_days_is_a_cadence CHECK (every_days BETWEEN 1 AND 366)
);

COMMENT ON TABLE group_schedules IS
    'When a group''s next cycle is due and how often after that. The worker opens a cycle at `next_due_at` and advances it by `every_days`; clearing the row stops the cadence. ADR 0057.';

-- No index beside the primary key. The worker's due query is `WHERE next_due_at <= now() ORDER BY next_due_at`, and the
-- table holds one row per group in the deployment - so the scan it would avoid is over a table whose size is the number
-- of groups a deployment has, and an index nobody measured is a write cost nobody chose. If a deployment ever has enough
-- groups for that to matter, this file and `JooqDueSchedules` are where the two lines go.
