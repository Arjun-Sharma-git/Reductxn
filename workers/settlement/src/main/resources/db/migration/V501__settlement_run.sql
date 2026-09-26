-- The settlement run and its plan, owned by workers/settlement (RULE B17).
--
-- `V501`: this module owns `5xx`. The ranges are `apps/api` 1xx, `libs/netting` 2xx, `libs/ledger` 3xx,
-- `libs/fx` 4xx, and `workers/settlement` 5xx, which `SchemaOwnershipTest` checks - Flyway keeps one version
-- sequence across every location, so a number claimed twice is a startup failure rather than a warning.
--
-- **The plan is stored, and it is a decision rather than a derived value.** Everything here could be
-- recomputed: `libs/planning` is pure, and the same cycle and the same tax rules produce the same plan
-- (ADR 0012). But the plan is what the group is asked to approve and what a rail is then asked to move, so
-- the rows are the record of that promise. A run that re-derived its plan on every read could settle a cycle
-- the group did not approve, and nothing in the database would say so. Note what this is NOT: `net_positions`
-- is a *plan's* position at a moment, not a stored account balance - L5's rule that a balance is a projection
-- holds, and a member's position is still computed from the ledger rather than read from here.
--
-- The plan's shape is per currency throughout, and there is no global total anywhere. Adding yen to rupees is
-- not addition (ADR 0012 point 4).

CREATE TABLE settlement_cycles (
    id         uuid        PRIMARY KEY,

    -- No foreign key to `groups`: that table belongs to apps/api (RULE B17), and a constraint across an
    -- ownership boundary would make one deployable's migrations a prerequisite of the other's.
    group_id   uuid        NOT NULL,

    -- The instant the cycle is cut off at, which is also the instant every rate, tax rule and obligation is
    -- resolved at. It is the key of the cycle rather than "now": a cycle is a claim about a moment, and a run
    -- must be reproducible from the rows the moment implies.
    as_of      timestamptz NOT NULL,

    created_at timestamptz NOT NULL,

    -- One cycle per group per instant. This is what makes opening a run idempotent at the database rather than
    -- only in the use case: a second attempt collides here instead of producing a second plan.
    CONSTRAINT settlement_cycles_are_unique_per_group_and_instant UNIQUE (group_id, as_of)
);

CREATE TABLE settlement_runs (
    id          uuid        PRIMARY KEY,
    group_id    uuid        NOT NULL,
    cycle_id    uuid        NOT NULL REFERENCES settlement_cycles (id),

    -- The lifecycle from docs/specs/settlement.md section 1. All eight states are named, and only four are
    -- reachable today - the constraint is the vocabulary, not a claim about what the code does.
    --
    -- `pending` when a run is opened, `computing` while the plan is being built, then either
    -- `awaiting_approval` or `awaiting_fx_funding` (RULE S4). `executing`, `done`, `rejected` and `failed`
    -- need quorum and a rail, which do not exist yet.
    status      text        NOT NULL,

    -- When the plan was computed. Distinct from `created_at` even though nothing is asynchronous yet: the
    -- spec says computing is asynchronous and the API answers 202, so the two will differ.
    computed_at timestamptz NOT NULL,
    created_at  timestamptz NOT NULL,

    -- One run per cycle: "a run is created per (group, cycle)" (section 1), and a second run over the same
    -- cycle would be a second answer to the same question.
    CONSTRAINT one_run_per_cycle UNIQUE (cycle_id),

    CONSTRAINT settlement_runs_have_a_known_status CHECK (status IN (
        'pending', 'computing', 'awaiting_approval', 'awaiting_fx_funding',
        'executing', 'done', 'rejected', 'failed'))
);

CREATE INDEX settlement_runs_by_group ON settlement_runs (group_id, created_at DESC);

-- The plan's per-currency summary: what the group is being asked to approve, per currency.
--
-- A fifth table, and the reason it exists is that the summary is not derivable from the rows below it. One
-- could sum the transfers and count them, and get the movements - but the plan's gross, its net, its tax and
-- its tax saving are `libs/netting`'s answers about the partition, and re-deriving them here would mean
-- running the engine again to read a run. That is exactly what storing the plan avoids.
--
-- It is also where the group's approval attaches: approving a run is approving these numbers.
CREATE TABLE settlement_partitions (
    run_id            uuid     NOT NULL REFERENCES settlement_runs (id),
    currency_code     text     NOT NULL,
    currency_exponent smallint NOT NULL,

    gross_minor       bigint   NOT NULL,
    net_minor         bigint   NOT NULL,
    tax_minor         bigint   NOT NULL,
    tax_saved_minor   bigint   NOT NULL,

    PRIMARY KEY (run_id, currency_code),

    CONSTRAINT partitions_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8),
    -- A magnitude per the definitions in docs/specs/netting.md section 2, plus the signed net movement.
    -- Checked because these four numbers are what a group reads before approving, and a negative gross would
    -- be a plan nobody could have produced.
    CONSTRAINT partitions_gross_is_a_magnitude CHECK (gross_minor >= 0),
    CONSTRAINT partitions_tax_is_not_negative CHECK (tax_minor >= 0),
    CONSTRAINT partitions_saving_is_not_negative CHECK (tax_saved_minor >= 0)
);

--
-- The clearing account is stored as an ordinary participant rather than in a column of its own, because that
-- is what it is: it is netted like any other participant so the transfers come out right (ADR 0012 point 3).
-- Its position is identified by its reserved sentinel id, which `libs/planning` owns the only model of
-- (RULE B21) - a boolean column here would be a second model of the same thing, and the two could disagree.
CREATE TABLE net_positions (
    run_id            uuid     NOT NULL REFERENCES settlement_runs (id),
    currency_code     text     NOT NULL,

    -- The exponent travels with the amount, as everywhere else: the row must mean what it meant when it was
    -- written, whatever happens to the currency catalogue afterwards.
    currency_exponent smallint NOT NULL,
    participant_id    uuid     NOT NULL,
    net_minor         bigint   NOT NULL,

    PRIMARY KEY (run_id, currency_code, participant_id),

    CONSTRAINT net_positions_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8),
    CONSTRAINT net_positions_participant_is_not_null_uuid CHECK (participant_id <> '00000000-0000-0000-0000-000000000000')
);

-- The transfers a run will ask a rail to move, and the state each one is in.
CREATE TABLE settlement_transfers (
    id                uuid     PRIMARY KEY,
    run_id            uuid     NOT NULL REFERENCES settlement_runs (id),
    currency_code     text     NOT NULL,
    currency_exponent smallint NOT NULL,

    from_user_id      uuid     NOT NULL,
    to_user_id        uuid     NOT NULL,
    amount_minor      bigint   NOT NULL,

    -- RULE S2's client-generated reference, derived from the transfer's own coordinates so that re-submitting
    -- is a no-op at the rail rather than a second movement. Unique, which is the half of "re-submitting the
    -- same transfer is a no-op" a database can hold: the second insert cannot happen.
    --
    -- The tuple in the spec is (run_id, from, to, amount) and it is incomplete: within one run, two
    -- currencies can produce the same three values - INR 100 and JPY 100 between the same pair - and they are
    -- two different movements. The currency is part of the reference here.
    rail_ref          text     NOT NULL,

    -- `pending | authorized | submitted | confirmed | failed`, from the same section. Nothing submits yet, so
    -- every row is `pending`; the vocabulary is here so that the first rail adapter does not have to change
    -- the schema to record what it did.
    status            text     NOT NULL,

    created_at        timestamptz NOT NULL,

    CONSTRAINT transfers_move_something CHECK (amount_minor > 0),
    CONSTRAINT transfers_are_between_two_parties CHECK (from_user_id <> to_user_id),
    CONSTRAINT transfers_have_a_unique_rail_reference UNIQUE (rail_ref),
    CONSTRAINT transfers_have_a_known_status CHECK (status IN (
        'pending', 'authorized', 'submitted', 'confirmed', 'failed')),
    CONSTRAINT transfers_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8)
);

-- Reading a run's plan back: by run, in a deterministic order. Ordering by `rail_ref` rather than by insertion
-- order is deliberate - a derived reference has a stable total order, while "the order the rows came back in"
-- is not an order at all, and a run whose reported order changed between two reads would make a plan the
-- group approved and a plan a rail executed look different when they are the same.
CREATE INDEX settlement_transfers_by_run ON settlement_transfers (run_id, rail_ref);
CREATE INDEX net_positions_by_run ON net_positions (run_id, currency_code);
