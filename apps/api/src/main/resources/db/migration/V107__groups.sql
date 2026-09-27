-- A group: a name, what it settles in, and who made it.
--
-- Owned by apps/api (MASTER-PLAN section 5.5), continuing its `V10x` range (RULE B17).
--
-- **V104 said this table would arrive with the thing that needs it, and this is that thing.** Its words: "a group in this
-- build is an identifier that rows agree on; nothing about a group is a property of the group, so a `groups` row would
-- hold an id, a timestamp and a name nobody has." What changed is not the tolerance for a name nobody reads - it is that
-- three operations were promised in the contract and cannot be answered without one: a list of the groups somebody belongs
-- to needs names to show, creating a group is *how* it gets a name and a base currency, and a group's own screen is the
-- row itself.
--
-- **The row is additive, and that is the load-bearing decision.** Every existing `group_id` in this database - in
-- obligations, members, cycles, invitations - is a bare uuid that no constraint ties to a group, and development data has
-- ids whose group nobody ever created. So this table does **not** become mandatory: rows keep accepting any uuid, a group
-- with commitments and no row keeps working, and what a row adds is a name, a currency and a creator. A foreign key from
-- `obligations.group_id` to here would have been the tidier schema and would have made every existing row unrecordable
-- (ADR 0043).
--
-- **`created_by` is a real user, unlike `group_members.user_id`.** Membership rows were written before identity existed
-- and therefore name uuids nobody can resolve; a group is created by somebody who had to sign in to do it, so this column
-- is always a `users` row. That asymmetry is the state of the build rather than a design: ADR 0041 and 0042 both record
-- that membership predates identity, and reconciling the two is its own decision.
--
-- **Idempotency is per creator, not per group.** `POST /groups` carries the contract's required idempotency key, and there
-- is no group in the path to scope it by - so the unique index is on the pair. A retry answers with the group the first
-- attempt created rather than a second group, and the write is `ON CONFLICT DO NOTHING` with a read-back, the shape the
-- obligations, members and invitations writes all use.

CREATE TABLE groups (
    id                    uuid        PRIMARY KEY,

    -- What the group is called, as the group wrote it. Not unique: two households can be called "Flat 3B", and a
    -- deployment that refused the second one would be inventing a rule nobody asked for.
    name                  text        NOT NULL,

    -- What the group settles in, as code plus exponent. The exponent is stored rather than joined at read time for the
    -- reason every amount in this build stores one: a rate or a total that was read under one exponent and is rendered
    -- under another is silently wrong, and the catalogue is not immutable over a deployment's life.
    currency_code         text        NOT NULL,
    currency_exponent     smallint    NOT NULL,

    -- Currency for headline totals spanning several currencies. Defaults to the settlement currency at creation, which
    -- is what the contract says, and is presentational: it restates nothing about anybody's obligation (ADR 0013).
    reporting_currency_code     text     NOT NULL,
    reporting_currency_exponent smallint NOT NULL,

    -- The zone that anchors the cycle boundary and tax effective dates (ADR 0014, point 6). **Nullable, and that is the
    -- honest state**: the contract makes it optional, most groups will not choose one at creation, and a deployment that
    -- defaulted to UTC would be deciding which cycle an obligation belongs to for a household in Kolkata. A group that
    -- has not chosen one cannot be scheduled yet, and that is a fact about the group rather than a gap in this file.
    settlement_timezone   text,

    -- How many members the group will hold. **Recorded and not enforced**, exactly as `group_members.role` is: nothing
    -- consults it, because making it a rule would mean the members write refusing a membership - and that write serves
    -- every group id in this database, including the ones with no row here. The CHECK is the contract's bound rather than
    -- a policy, so a number this build could never honour cannot be stored.
    max_members           integer     NOT NULL DEFAULT 200,

    created_by            uuid        NOT NULL,
    created_at            timestamptz NOT NULL,

    -- The idempotency scope: this creator, this key, one group.
    idempotency_key       text        NOT NULL,

    CONSTRAINT group_names_are_not_empty CHECK (length(btrim(name)) > 0),
    CONSTRAINT group_names_fit_the_contract CHECK (length(name) <= 120),
    CONSTRAINT group_currencies_are_well_formed CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT group_reporting_currencies_are_well_formed CHECK (reporting_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT group_exponents_are_not_negative CHECK (currency_exponent >= 0 AND reporting_currency_exponent >= 0),
    CONSTRAINT group_max_members_is_within_the_contract CHECK (max_members BETWEEN 1 AND 200),
    CONSTRAINT group_keys_are_not_empty CHECK (length(btrim(idempotency_key)) > 0)
);

-- Idempotency's index, and the constraint that makes it a promise rather than a hope: a retry with the same key by the
-- same person finds this row and writes nothing.
CREATE UNIQUE INDEX groups_by_creator_key ON groups (created_by, idempotency_key);

-- The list read: "the groups this person belongs to", which joins memberships to groups. `group_members_by_group` is
-- `(group_id, joined_at, user_id)` and serves the members read; it cannot serve this one, because the query's leading
-- column is the *person*. Without this index every group list scans every membership in the deployment.
CREATE INDEX group_members_by_user ON group_members (user_id, joined_at DESC, group_id);
