-- When the current spell of a rail's silence began, so a policy about patience has a duration to measure.
--
-- `V508` continues this module's `5xx` range.
--
-- **Why the start of the silence is a fact, and why the count is not enough.** `V506` stores how many calls in a
-- row went unanswered and when the next one may be made. Neither answers "how long has this been going on": the
-- count is a number of tries, and with an exponential backoff that number means a duration that changes whenever
-- `retry.base` or `retry.cap` is redeployed - so a policy written over the count ("escalate after eight tries")
-- would silently become a different policy the day somebody tuned the backoff. Escalation is a decision about
-- *time* ("how long is too long"), so the time it is measured from has to be stored.
--
-- **`unanswered_since` is the first unanswered call of a spell, and an answer ends the spell.** `markTransfer`
-- clears it along with the count and the wait, because a rail with an opinion is a working rail. `markTransfer`
-- *attempted* deliberately does not clear it: offering the instruction again is part of the same silence rather
-- than the end of it, which is the distinction that makes the duration mean "how long has nobody heard from this
-- rail". A transfer the rail has never been asked has no start, so the column is nullable and null means "the rail
-- has answered, or has not been asked" - the same shape as `next_attempt_at`, and for the same reason.
ALTER TABLE settlement_transfers ADD COLUMN unanswered_since timestamptz;

-- A silence begins with an unanswered call, so a start with no count is not a fact about a rail - it is a writer
-- that forgot which two columns belong together.
ALTER TABLE settlement_transfers ADD CONSTRAINT transfers_silences_have_an_attempt
    CHECK (unanswered_since IS NULL OR unanswered_attempts > 0);

-- The partial index the escalation report needs: transfers a rail has been silent on, oldest silence first.
-- A transfer with an answer is not in this index at all, so it holds exactly the transfers an operator has to look
-- at - which is a set that ends rather than a set that grows with every cycle ever settled.
CREATE INDEX settlement_transfers_silent ON settlement_transfers (unanswered_since)
    WHERE unanswered_since IS NOT NULL;
