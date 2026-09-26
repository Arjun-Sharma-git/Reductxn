-- When a rail accepted a transfer, so that being at the rail can have an age.
--
-- `V510` continues this module's `5xx` range, and it is the column `V503` promised: "the reconciliation sweep in
-- section 4.4 will measure a stuck transfer against the moment it was submitted, and that means a timestamp and a
-- partial index on `status = 'submitted'` - but nothing reads either today, and a column nothing reads is a claim
-- rather than a fact. It arrives with the sweep, in one `ALTER TABLE`, which is what the version range is for."
-- The reader it was waiting for is the report a person reads (ADR 0026).
--
-- **Why a submission needs a stored instant.** `V508` stores when a spell of silence began, and silence is not the
-- only way a transfer gets stuck at a rail. A rail that keeps answering "still pending" *is* answering: nothing goes
-- unanswered, the retry counter resets on every poll, and the transfer can sit at the rail for a week while every
-- measure this module had said it was fine. The age of the submission is the fact that sees it - and like the
-- silence's it has to be stored, because a count of polls or attempts means a different amount of time whenever the
-- driver's window, the batch size or the backoff changes.
--
-- **The first acceptance starts the wait, and a later "still pending" does not restart it.** `markTransfer` writes
-- `coalesce(submitted_at, :at)` when the rail answers that it has the transfer - the same shape as
-- `unanswered_since`, and for the same reason: an age measured from the most recent poll would restart on every
-- poll, and a transfer at the rail since Monday would look freshly submitted every fifteen minutes.
--
-- **Null means "the rail never accepted this transfer", and it is deliberately not backfilled.** A transfer
-- confirmed on the first call was never in flight, so it has no submission instant and never will have one - and a
-- row that was already `submitted` when this column arrived has no honest value for it: the plan's `created_at` is
-- when the group's cycle was opened, not when a rail took the instruction, and inventing an instant in a column
-- whose only purpose is to be measured against a threshold would be a worse lie than a null. Such a row is stamped
-- the first time the worker polls it, which is one pass away, and until then it is reported by its silence instead.
-- That is also why there is no CHECK tying `status = 'submitted'` to a non-null instant: the one kind of row that
-- cannot satisfy it is a row that predates the column, and a migration's job is not to guess.
ALTER TABLE settlement_transfers ADD COLUMN submitted_at timestamptz;

-- The partial index the reconciliation report needs: transfers at the rail, oldest submission first. A transfer the
-- rail never accepted is not in it, and neither is a confirmed or failed one, so it holds exactly the movements a
-- person may have to chase - a set that ends, rather than one that grows with every cycle ever settled.
CREATE INDEX settlement_transfers_at_the_rail ON settlement_transfers (submitted_at)
    WHERE status = 'submitted';
