-- The ledger: journal entries and their postings (docs/specs/ledger.md section 1).
--
-- Owned by libs/ledger, which is why this file lives beside it and not in platform/persistence - that
-- module runs migrations, it does not own tables (RULE B17). The owning module is the one that knows what
-- an entry is, which is why it also holds JournalEntry, Posting and the two verifiers.
--
-- `V301`: libs/ledger owns 3xx. See V101 in apps/api for the version scheme - Flyway keeps one sequence
-- across every location it reads, so a version has to identify its owner as well as its order.
--
-- This is the first schema in the repository that a *write* path touches, so the constraints here do more
-- work than the ones in `obligations`. Four of the ledger's invariants are made unrepresentable rather
-- than checked in code:
--
--   L2  a posting's amount is strictly positive, and direction carries the sign
--   L4  entries are chained per (group, currency): one successor per predecessor, one genesis
--   L6  every posting is in its entry's currency - by a composite foreign key, not by a comparison
--   L9  a link id carries at most one base entry and one quote entry
--
-- L1 (an entry balances) is deliberately *not* here. It is a property of a set of rows rather than of one,
-- so the database could only check it with a deferred constraint trigger, and the type already makes an
-- unbalanced entry impossible to construct (JournalEntry). What the offline verifier adds is a check on
-- the rows themselves, which is the half that survives somebody with a SQL prompt (EntryChain, L4 section
-- 3 of the spec).
--
-- L3 (append-only) is not here, and that is a known gap with a reason: this migration runs as the
-- application's own role, and a table's owner can always UPDATE and DELETE whatever the grants say - so
-- revoking from `reductxn_app` would change nothing until the tables are owned by a separate migration
-- role. Stated rather than left to look enforced (MASTER-PLAN section 5.5, section 6).

CREATE TABLE journal_entries (
    id                uuid        PRIMARY KEY,
    group_id          uuid        NOT NULL,

    -- Integer minor units, and the exponent travels with them. Not resolved from `currencies` at read
    -- time: an entry is history, and history that changes meaning when reference data changes is not a
    -- record of anything.
    currency_code     text        NOT NULL,
    currency_exponent smallint    NOT NULL,

    occurred_at       timestamptz NOT NULL,

    -- What the entry records, matching EntryKind. A constraint rather than a convention, because a kind
    -- outside the set is invisible to every report that branches on one.
    kind              text        NOT NULL,

    created_by        uuid,
    idempotency_key   text,

    -- The FX linkage pair. Both or neither: one without the other is not partially linked, it is
    -- unverifiable, because L9 needs the id to find the partner and the role to know which side this is.
    fx_link_id        uuid,
    fx_role           text,

    rail_performed_fx boolean     NOT NULL,

    -- The chain. `prev_hash` is null exactly at a chain's start. `hash` is stored because it is what the
    -- offline verifier compares, not because it is the source of truth - JournalEntry derives it, so the
    -- column can only ever hold the derived value.
    prev_hash         text,
    hash              text        NOT NULL,

    CONSTRAINT journal_entries_kind_is_known
        CHECK (kind IN ('obligation', 'settlement', 'fee', 'tax', 'fx', 'adjustment')),
    CONSTRAINT journal_entries_exponent_in_range
        CHECK (currency_exponent BETWEEN 0 AND 8),
    CONSTRAINT journal_entries_fx_pairing
        CHECK ((fx_link_id IS NULL) = (fx_role IS NULL)),
    CONSTRAINT journal_entries_fx_role_is_known
        CHECK (fx_role IS NULL OR fx_role IN ('base', 'quote')),
    CONSTRAINT journal_entries_hash_is_a_digest
        CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT journal_entries_prev_hash_is_a_digest
        CHECK (prev_hash IS NULL OR prev_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT journal_entries_key_is_meaningful
        CHECK (idempotency_key IS NULL OR btrim(idempotency_key) <> ''),

    -- Lets postings carry the currency and still be provably in their entry's currency (L6). A foreign key
    -- on `id` alone cannot express that, and a CHECK cannot read another table.
    CONSTRAINT journal_entries_currency_is_addressable
        UNIQUE (id, currency_code, currency_exponent)
);

-- The chain read: what a booking asks for before it can build an entry, and what an offline verifier walks.
CREATE INDEX journal_entries_chain
    ON journal_entries (group_id, currency_code, occurred_at, id);

-- L4, structurally. Two entries in one chain cannot claim the same predecessor, so the failure a
-- per-(group, currency) lock exists to prevent - two writers reading the same head - is impossible rather
-- than merely unlikely. The lock is still needed: this index turns a lost race into a failed transaction,
-- and the lock is what stops that failure being the normal outcome under load.
CREATE UNIQUE INDEX journal_entries_one_successor_per_hash
    ON journal_entries (group_id, currency_code, prev_hash)
    WHERE prev_hash IS NOT NULL;

-- And exactly one entry may start a chain. Postgres treats nulls as distinct in a unique index, so the
-- partial predicate above leaves "no predecessor" unconstrained, and a chain could otherwise have any
-- number of first entries.
CREATE UNIQUE INDEX journal_entries_one_genesis
    ON journal_entries (group_id, currency_code)
    WHERE prev_hash IS NULL;

-- L9, structurally, for the half that can happen by accident: a retried append producing a second base
-- entry under one link id. It does not prove the partner exists - that is FxLinkage's job over the rows -
-- but it does prove there can never be two of the same side.
CREATE UNIQUE INDEX journal_entries_one_entry_per_fx_role
    ON journal_entries (fx_link_id, fx_role)
    WHERE fx_link_id IS NOT NULL;

-- No unique index on (group_id, idempotency_key), and the absence is deliberate. A cross-currency booking
-- writes two entries carrying the same key and the same group, so such an index would refuse a valid pair;
-- excluding fx_role to allow for it would leave the single-currency case - where fx_role is null - with
-- nulls distinct and therefore unconstrained. Idempotency is enforced on `obligations`, which is where the
-- key belongs; on an entry it is the second, weaker signal JournalEntry's documentation says it is.

CREATE TABLE postings (
    id                uuid     PRIMARY KEY,
    entry_id          uuid     NOT NULL,
    account_id        uuid     NOT NULL,

    -- Which posting of its entry this is. Stored rather than implied, and this is the column that was
    -- missing from the first draft of this migration.
    --
    -- `EntryHash` deliberately covers postings in the order they are held, so that a reordering a reader
    -- would see is detectable rather than normalised away - which means an entry can only be verified from
    -- its rows if the order it was written in can be read back. Deriving the primary key from a posting's
    -- position does not do that: the key is a UUID, which does not sort in position order, so `ORDER BY id`
    -- returns the postings shuffled and the entry rehashes to a different value.
    position          smallint NOT NULL,

    -- 'D' or 'C'. A magnitude plus a direction, never a signed amount (L2).
    direction         text     NOT NULL,
    amount_minor      bigint   NOT NULL,

    currency_code     text     NOT NULL,
    currency_exponent smallint NOT NULL,

    CONSTRAINT postings_position_is_in_range CHECK (position >= 0),
    CONSTRAINT postings_position_is_unique_per_entry UNIQUE (entry_id, position),
    CONSTRAINT postings_direction_is_known CHECK (direction IN ('D', 'C')),

    -- L2. A zero posting would balance for free, which is how a missing posting hides inside an entry that
    -- still appears to balance.
    CONSTRAINT postings_amount_is_positive CHECK (amount_minor > 0),
    CONSTRAINT postings_exponent_in_range CHECK (currency_exponent BETWEEN 0 AND 8),

    CONSTRAINT postings_are_in_their_entry
        FOREIGN KEY (entry_id) REFERENCES journal_entries (id),

    -- L6 as a foreign key rather than a comment. A posting whose currency differs from its entry's cannot
    -- be inserted, because this references the unique key above rather than the entry's id.
    CONSTRAINT postings_are_in_their_entrys_currency
        FOREIGN KEY (entry_id, currency_code, currency_exponent)
        REFERENCES journal_entries (id, currency_code, currency_exponent)
);

-- L5's measurement: a position is a projection over postings, so the projection has to find them.
CREATE INDEX postings_by_account ON postings (account_id, currency_code);

-- No `ledger_accounts` here, and the absence is a decision rather than an omission. Section 5.5 assigns it
-- to this module, but an account row needs an owner and a type and neither exists yet: a member *is* its id
-- today (identity and groups are unbuilt), and the clearing account is the reserved constant
-- `FxClearing.ACCOUNT_ID` in libs/planning, which RULE B2 stops this module from reading. So postings carry
-- an account id with deliberately no foreign key - the state `obligations.group_id` is in too - and the
-- table arrives with the first thing that has a type. What that type should be for `fx_clearing` is an open
-- question in docs/specs/ledger.md section 6 rather than a guess made here.
