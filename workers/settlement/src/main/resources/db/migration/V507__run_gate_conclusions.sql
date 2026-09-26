-- What a run's gates concluded, the last time the worker looked at it (ADR 0023).
--
-- `V507` continues this module's `5xx` range, and this table exists for one reason: a member reading a run is
-- `apps/api`'s surface, and the question a member actually has - "why is this waiting?" - is this module's
-- arithmetic. Quorum needs the run's participants and who owes what; funding needs the plan's clearing positions
-- and what has arrived. Neither can be projected by a reader without re-deriving a rule (ADR 0021), so the rule's
-- author writes its conclusion down and the reader projects that.
--
-- **One row per run, replaced in place.** The conclusion is not a history: it is what the gates say *now*, and a
-- reader that had to choose between two conclusions would need a rule about which one is current. `concluded_at`
-- is the answer to "how old is this", and it is rewritten on every look - so it means "when the worker last read
-- these gates", not "when this verdict was first reached", which is why the write is unconditional rather than
-- skipped when nothing changed.
--
-- **`status` mirrors `settlement_runs.status` at the moment the gates were read.** A projection that outlives its
-- subject has to carry the subject's version: a run that was waiting when the gates were read and is `done` now
-- would otherwise have a conclusion that says it is one approval short. No `CHECK` names the eight states here -
-- the vocabulary is `V501`'s and a second copy would be a second thing to keep in step - and the writer is a Java
-- enum, so a word outside it cannot be written by accident.
--
-- **`debtors_still_to_confirm` is a count, not a list.** *Who* still has to confirm is a member-level fact, and
-- the run's members are `net_positions`' - this table would be the second place the member set is written down.
-- The count is what the verdict needs in order to explain itself ("a majority has approved and a debtor has not
-- confirmed" is `met = false` with `debtors_still_to_confirm > 0`), and naming the member is a different gap:
-- publishing the run's participants, which the read model does not do yet.
CREATE TABLE settlement_run_gates (
    run_id                   uuid        PRIMARY KEY REFERENCES settlement_runs (id),

    status                   text        NOT NULL,

    -- RULE S1's verdict: a majority of the run's members, and every debtor personally.
    quorum_met               boolean     NOT NULL,
    approvals_counted        integer     NOT NULL,
    approvals_required       integer     NOT NULL,
    debtors_still_to_confirm integer     NOT NULL,

    -- RULE S4's verdict. The amounts that are still missing are rows in the table below, and there is one per
    -- currency rather than a total, because nothing here adds rupees to yen (ADR 0012 point 4).
    funding_satisfied        boolean     NOT NULL,

    concluded_at             timestamptz NOT NULL,

    CONSTRAINT settlement_run_gates_counts_are_not_negative
        CHECK (approvals_counted >= 0 AND approvals_required >= 0 AND debtors_still_to_confirm >= 0),

    -- The two invariants a verdict cannot contradict, as constraints rather than as a comment. `Quorum.met()` is
    -- the rule, and both of these follow from it: a run with quorum has counted at least the majority it needs,
    -- and every debtor has approved. A writer that got either wrong would be storing a conclusion no reader could
    -- act on, and this refuses it at the row instead of at the reader.
    CONSTRAINT settlement_run_gates_met_means_a_majority
        CHECK (NOT quorum_met OR approvals_counted >= approvals_required),
    CONSTRAINT settlement_run_gates_met_means_every_debtor_confirmed
        CHECK (NOT quorum_met OR debtors_still_to_confirm = 0)
);

-- What is still missing, per currency, for the run's external requirement.
--
-- **Rows rather than an array or a total**: an amount carries a currency and an exponent, so it is a row like every
-- other amount in this schema, and the exponent travels with it so the gap means what it meant when it was
-- written. **And only the gaps**: a currency that is covered has no row here, which is the same choice
-- `Funding.outstanding()` makes - zero is not a fact worth storing, and a reader that has to filter zeros out of a
-- list is a reader doing arithmetic the writer already did.
CREATE TABLE settlement_run_gate_shortfalls (
    run_id            uuid     NOT NULL REFERENCES settlement_run_gates (run_id),

    currency_code     text     NOT NULL,
    currency_exponent smallint NOT NULL,
    amount_minor      bigint   NOT NULL,

    PRIMARY KEY (run_id, currency_code),

    CONSTRAINT settlement_run_gate_shortfalls_are_amounts CHECK (amount_minor > 0),
    CONSTRAINT settlement_run_gate_shortfalls_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8)
);
