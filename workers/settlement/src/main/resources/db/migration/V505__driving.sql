-- When a run was last driven, so a cadence can drive what needs it without driving it twice.
--
-- `V505` continues this module's `5xx` range.
--
-- **Why a column on the run rather than on the transfer.** V503 said the sweep's timestamp would arrive with the
-- sweep, and that it would be `submitted_at` on `settlement_transfers`, measured against a threshold. Building it
-- showed the better place is one level up. A run, not a transfer, is the unit of work: `ExecuteRun` already walks
-- its transfers and decides for each whether to submit or poll, so a per-transfer timestamp would be a second
-- mechanism for what the pass already does. What the pass needed was not a threshold on a transfer but a
-- *guard against being run twice* - by two worker instances, or by the same one twice in a minute - and that is
-- a fact about the run.
--
-- **The threshold is a rate limit, not a policy.** Nothing about a settlement decides how often to look at it;
-- how often to look is an operational setting, because it costs rail calls and database round trips. So
-- `driven_at` is written by the driver *before* it drives (the order section 4.2 requires, so a crash leaves a
-- mark rather than a run that looks untouched), and a run is due when it has not been attempted within the
-- window.
--
-- **Nullable, and null means "never driven"** rather than a default instant. A run opened a second ago has never
-- been offered to a rail, and a default of `created_at` would have been a claim about the past that nothing
-- observed - the same reason `rail_detail` is nullable and `occurred_at` is supplied rather than read.
ALTER TABLE settlement_runs ADD COLUMN driven_at timestamptz;

-- The due query: runs whose progress is possible, oldest attempt first. Partial, because two of the eight states
-- are terminal and a third pair (`pending`, `computing`) exists only inside the transaction that opens a run -
-- so the index covers the three states a driver can act on and stays small as settled runs accumulate.
--
-- `driven_at ASC NULLS FIRST` is the order the query uses, and it is what stops starvation: a run that a gate
-- keeps refusing is attempted once per window and then goes to the back of the queue, so a backlog of runs
-- waiting on a group's approval cannot keep a run that is ready to settle from ever being looked at.
CREATE INDEX settlement_runs_due ON settlement_runs (driven_at)
    WHERE status IN ('awaiting_approval', 'awaiting_fx_funding', 'executing');
