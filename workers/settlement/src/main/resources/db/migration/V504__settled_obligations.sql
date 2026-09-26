-- The commitments a run settles, so that settling them is a fact rather than an inference.
--
-- `V504` continues this module's `5xx` range.
--
-- **Why this table has to exist.** A run nets everything open at its instant, and once its transfers are
-- confirmed the commitments it netted are discharged - no later cycle may net them again, or the group would
-- move money twice for one promise. The question is how a reader knows which obligations are discharged, and
-- the tempting answer is to derive it: "an obligation is settled when a done run exists for its group at an
-- instant at or after it was incurred". That answer is wrong, and the way it is wrong is worth writing down.
-- `incurred_at` is supplied by the client (P4) and may be *backdated*: an obligation incurred last Tuesday,
-- recorded today, after a run over the whole of last week, would be derived as settled by a run that never saw
-- it - silently un-settleable, and invisible until a member asks why their debt is still open. So the ids are
-- recorded when the plan is, and the derivation is a lookup rather than a guess.
--
-- **No foreign key to `obligations`.** That table is `apps/api`'s (RULE B17), and a constraint across an
-- ownership boundary would make one deployable's migrations a prerequisite of the other's - which is exactly
-- what `settlement_cycles.group_id` avoids for the same reason.
--
-- **Rows here are the plan's input, not its outcome.** They are written when a run is computed, with the plan,
-- in one transaction: a run that recorded a plan but not the commitments it was computed from could not
-- answer which promises it covered. Whether they are *settled* is a separate question and it is not a column
-- here: it is the run's status. A run that is `failed` has moved some of the group's money and has settled
-- nothing - that is RULE S3 in the spec's own words ("net positions are not marked settled") - so the
-- exclusion a reader applies is `status = 'done'`, and it is one join away rather than one column to keep in
-- step.
CREATE TABLE settlement_run_obligations (
    run_id        uuid NOT NULL REFERENCES settlement_runs (id),

    -- The obligation's identity, as `apps/api` wrote it. Deliberately no cascade: nothing deletes an
    -- obligation (it is voided), so a row here can only disappear with its run.
    obligation_id uuid NOT NULL,

    PRIMARY KEY (run_id, obligation_id)
);

-- The question a reader asks: "is this obligation settled?", which is this index and a join to the run's
-- status. The primary key answers the other direction - "what did this run settle?" - which is what a report
-- of a run will ask.
CREATE INDEX settlement_run_obligations_by_obligation ON settlement_run_obligations (obligation_id);
