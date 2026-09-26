-- Quorum and funding: the two things a computed run waits for, owned by workers/settlement (RULE B17).
--
-- `V502` continues this module's `5xx` range. Both tables are about a run that has already been computed and
-- recorded, which is why they are here rather than in V501: a run's plan is what it *will* do, and these two are
-- what other people and other systems have since said about it.

-- A member's answer on a run. RULE S1: a majority of members, and every member who owes a net amount.
--
-- **The key is `(run_id, member_id)`, and that is the "a decision is final" rule at the database.** A member who
-- has answered cannot answer again, so nobody has to work out what a withdrawal of consent means for the
-- approvals that came after it - a rule the use case could not enforce against two concurrent callers, and this
-- can.
--
-- No foreign key to a `group_members` table: there is none, and membership is `apps/api`'s (see ADR 0016). The
-- members a run concerns are the participants in its own plan, so the check that an answerer is one of them is
-- made against `net_positions` by the use case - where the refusal can name the reason - rather than by a
-- constraint here that would have nothing to point at.
CREATE TABLE settlement_decisions (
    run_id     uuid        NOT NULL REFERENCES settlement_runs (id),
    member_id  uuid        NOT NULL,

    -- `approve | reject`. Two words rather than a boolean, because "no" and "not yet" are different answers and
    -- the lifecycle has a state for each: a run is `rejected` because somebody refused it, not because nobody
    -- has approved it.
    decision   text        NOT NULL,

    decided_at timestamptz NOT NULL,

    PRIMARY KEY (run_id, member_id),

    CONSTRAINT settlement_decisions_are_known CHECK (decision IN ('approve', 'reject')),
    CONSTRAINT settlement_decisions_come_from_a_member
        CHECK (member_id <> '00000000-0000-0000-0000-00000000f0c1')
);

-- Currency arriving from outside to cover a run's external requirement (RULE S4).
--
-- **This table exists because reductxn cannot move the money itself (P2).** The group's currency arrives through
-- something outside - a member's bank, a treasurer, a rail that performs FX - and this is the record of it. The
-- run's gate is then a question about these rows and the plan's requirement: is every currency the plan needs
-- covered by rows that say it arrived?
--
-- **`funding_ref` is RULE S2's idempotency key for this path, and it is unique globally rather than per run.**
-- The reference is whatever identifies the arrival - an external transfer id, a treasurer's entry - and that
-- identifies one arrival in the world. Scoping it per run would let one arrival cover two runs' requirements,
-- which is the same money counted twice, and that is the failure the gate exists to prevent.
--
-- Partial fundings are rows like any other: a currency covered by two arrivals is two rows, and the gate sums
-- them. `amount_minor` is positive for the same reason a transfer's is - the table records money arriving, and a
-- correction is another row rather than a negative one.
CREATE TABLE settlement_fx_fundings (
    id                uuid     PRIMARY KEY,
    run_id            uuid     NOT NULL REFERENCES settlement_runs (id),

    -- The exponent travels with the amount, as everywhere else: the row must mean what it meant when it was
    -- written, whatever happens to the currency catalogue afterwards.
    currency_code     text     NOT NULL,
    currency_exponent smallint NOT NULL,
    amount_minor      bigint   NOT NULL,

    funding_ref       text     NOT NULL,
    funded_at         timestamptz NOT NULL,

    CONSTRAINT fundings_are_positive CHECK (amount_minor > 0),
    CONSTRAINT fundings_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8),
    CONSTRAINT fundings_reference_is_meaningful CHECK (btrim(funding_ref) <> ''),
    CONSTRAINT fundings_have_a_unique_reference UNIQUE (funding_ref)
);

-- Reading a run's funding: by run, and by reference when a retry asks whether it already recorded one.
CREATE INDEX settlement_fx_fundings_by_run ON settlement_fx_fundings (run_id);
