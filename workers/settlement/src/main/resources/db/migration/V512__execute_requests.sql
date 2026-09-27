-- A member asking for this run's transfers to be offered to the rail now, rather than on the driver's next window.
--
-- `V512` continues this module's `5xx` range.
--
-- **Why this is a column and not a call.** `POST /runs/{runId}/execute` is served by `apps/api`, and the use case that
-- would honour it - `ExecuteRun`, behind `DriveRuns.driveOne` - lives here. The two deployables do not depend on each
-- other (RULE B6, ADR 0020), so the request travels the way every other member intent travels in this project: as a
-- row the API writes and this worker reads, exactly as `settlement_cycles` carries "close the books at this instant"
-- from the API to the cadence that computes it.
--
-- **Why a new column rather than reusing `driven_at`.** `V505`'s `driven_at` is a guard against driving a run twice,
-- and its *value* is the audit of when the pass last looked. A request that wrote `driven_at = NULL` to make a run due
-- would erase that audit, and would be indistinguishable from a run nobody has ever driven.
--
-- **The request is consumed by the attempt, and the attribution is not.** `markDriven` clears
-- `execute_requested_at` when it marks the attempt, because a request that outlived its attempt would keep the run
-- permanently due inside the window and defeat `V506`'s backoff for that run forever.
-- `execute_requested_by` stays: it is a record of who asked, which is a fact about the run's history rather than a
-- pending flag - the same split, for the same reason, as the run's own `rail_detail`.
ALTER TABLE settlement_runs ADD COLUMN execute_requested_at timestamptz;
ALTER TABLE settlement_runs ADD COLUMN execute_requested_by uuid;

-- No index for the request on its own: the due query is already bounded by the partial index `V505` created
-- (`status IN ('awaiting_approval', 'awaiting_fx_funding', 'executing')`), so the extra disjunct is evaluated over
-- the runs a driver could act on - a handful - rather than over every run a group has ever settled. A second index
-- would be a write cost on the table that grows forever, bought for a predicate that is already narrow.
COMMENT ON COLUMN settlement_runs.execute_requested_at IS
    'When a member last asked for this run to be offered to the rail; cleared when the driver attempts it';
COMMENT ON COLUMN settlement_runs.execute_requested_by IS
    'Who asked, from the API session. Kept after the attempt, unlike execute_requested_at (ADR 0050)';
