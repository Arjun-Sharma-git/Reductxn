-- Obligations: what a group's members have promised each other.
--
-- Owned by apps/api (MASTER-PLAN section 5.5), which is why this file lives here and not in
-- platform/persistence - that module runs migrations, it does not own tables.
--
-- `V101`, not `V1`. Migrations live beside their owning module (RULE B17), and Flyway keeps ONE version
-- sequence per schema regardless of how many locations it reads - so a `V1` here and a `V1` in
-- libs/netting are "more than one migration with version 1" and the application refuses to start. The
-- version range namespaces the sequence per owner: apps/api owns 1xx, libs/netting owns 2xx, and a test
-- checks that the range matches the module a file lives in. It also makes cross-module ordering explicit,
-- which is better than it being accidental.
--
-- Append-only in the sense that matters (ADR 0004): voiding an obligation sets `voided_at` rather than
-- deleting the row, so what was promised stays on the record and a re-run of a historical cycle sees the
-- same thing it saw the first time.

CREATE TABLE obligations (
    id                uuid        PRIMARY KEY,
    group_id          uuid        NOT NULL,
    debtor_id         uuid        NOT NULL,
    creditor_id       uuid        NOT NULL,

    -- Integer minor units. `numeric` would invite a decimal, and a decimal in money is the bug P1 exists
    -- to prevent.
    amount_minor      bigint      NOT NULL,

    -- The exponent is stored with the amount rather than resolved from a currency catalogue at read time.
    -- An obligation records what was promised: if its meaning depended on current reference data, a
    -- historical obligation could change meaning without anyone editing it.
    currency_code     text        NOT NULL,
    currency_exponent smallint    NOT NULL,

    incurred_at       timestamptz NOT NULL,

    -- Set when voided. Never a delete, and never a null-out of anything above.
    voided_at         timestamptz,

    CONSTRAINT obligations_debtor_is_not_creditor CHECK (debtor_id <> creditor_id),
    CONSTRAINT obligations_amount_is_positive      CHECK (amount_minor > 0),
    CONSTRAINT obligations_exponent_in_range       CHECK (currency_exponent BETWEEN 0 AND 4),
    -- A void cannot precede the obligation it voids.
    CONSTRAINT obligations_void_follows_incurrence CHECK (voided_at IS NULL OR voided_at >= incurred_at)
);

-- The preview's only query: everything open in a group at an instant. Partial, because a voided
-- obligation is never what it is looking for and the index should not carry them.
CREATE INDEX obligations_open_by_group
    ON obligations (group_id, incurred_at)
    WHERE voided_at IS NULL;

-- No foreign key to a groups table: apps/api owns `groups` too (section 5.5), but it does not exist yet.
-- Stated here rather than left to be noticed, because a missing foreign key usually means somebody
-- forgot one.
