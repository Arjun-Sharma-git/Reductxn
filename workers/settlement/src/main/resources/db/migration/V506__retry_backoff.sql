-- How many times a transfer has been attempted without the rail answering, and when it may be tried again.
--
-- `V506` continues this module's `5xx` range.
--
-- **Why this state belongs on the transfer while `V505`'s mark belongs on the run.** They answer different
-- questions. A run's `driven_at` guards against driving the same *run* twice (two instances, two ticks), which is
-- a fact about the run. A backoff guards one *transfer's* calls to a rail that is not answering, and the evidence
-- - how many times in a row this specific instruction went unanswered - is a fact about the transfer. A
-- per-transfer timestamp was wrong for the driver's window and is right here, and the difference is that this one
-- has a reader: `ExecuteRunService` skips a transfer whose `next_attempt_at` has not arrived.
--
-- **`unanswered_attempts` counts consecutive calls the rail did not answer**, and any answer resets it to zero -
-- including an answer that refuses the transfer, because a rail with an opinion is a working rail. It grows only
-- while a rail is unreachable, which is what makes it the right input for an exponential backoff: a transfer that
-- has been polled successfully fifty times is not due a fifteen-minute wait because of a blip.
--
-- **`next_attempt_at` is nullable and null means "now".** A transfer that the rail has answered, or one that has
-- never been offered, has no reason to wait; only an unanswered call sets it. Storing the wait as an instant
-- rather than as a delay means the decision is made once, when the failure happens, and read by everyone
-- afterwards without anybody re-deriving it from a duration and a clock.
ALTER TABLE settlement_transfers ADD COLUMN unanswered_attempts integer NOT NULL DEFAULT 0;
ALTER TABLE settlement_transfers ADD COLUMN next_attempt_at timestamptz;

ALTER TABLE settlement_transfers ADD CONSTRAINT transfers_have_a_sane_attempt_count
    CHECK (unanswered_attempts >= 0);

-- The partial index the driver's due query needs: unresolved transfers, by when they may be tried again.
-- `confirmed` and `failed` are terminal, so a row that is neither can never join this index again - which keeps
-- it small in a table that grows with every settled cycle and is read forever.
CREATE INDEX settlement_transfers_due ON settlement_transfers (next_attempt_at)
    WHERE status IN ('pending', 'authorized', 'submitted');
