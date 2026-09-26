-- Currencies: the minor-unit exponent, as data (docs/specs/fx.md, MASTER-PLAN section 5.5).
--
-- Owned by libs/fx rather than libs/ledger because the exponent is what conversion needs first, and because
-- a currency is reference data rather than a ledger concept. Section 5.5's note says exactly that; this
-- file is where it stops being a note.
--
-- `V401`: libs/fx owns 4xx. See V101 in apps/api for the version scheme.
--
-- The reason this table arrives now, before rates or quotes, is a contract one. `Money` on the wire is
-- `{ amountMinor, currency }` - a code and no exponent - and `Currency` in libs/ledger requires both. So
-- every request that carries an amount needs the exponent resolved server-side before it can be turned
-- into money at all, which makes the catalogue a prerequisite of the first write path rather than a
-- convenience for reporting. Resolving it from a constant table in Java was the alternative, rejected for
-- the reason this whole schema exists: P1 is a rule about exponents, and an exponent compiled into a
-- release is one that cannot be corrected without one.
--
-- `minor_unit_exponent` is deliberately constrained to the range Currency accepts (0..8). Two places
-- declaring that range is a risk, and it is the accepted direction of risk: the column cannot accept
-- something the type would refuse, so the range is checkable here and the type stays the guard in code.

CREATE TABLE currencies (
    code                text     PRIMARY KEY,

    -- Fractional digits: INR 2, JPY 0. The whole reason this table exists.
    minor_unit_exponent smallint NOT NULL,

    -- Whether the jurisdiction's CBDC ledger code is this currency, which decides how a settlement rail is
    -- addressed. The rail adapter is not written yet; the flag is here because a currency row without it
    -- would have to be corrected by a migration rather than an insert.
    is_cbdc             boolean  NOT NULL,

    -- The jurisdiction this currency is legal tender in, where that is a single answer. Nullable because
    -- for a cross-border currency it is not, and a guess would then be a claim about tax law (ADR 0006).
    jurisdiction        text,

    CONSTRAINT currencies_exponent_in_range CHECK (minor_unit_exponent BETWEEN 0 AND 8),
    CONSTRAINT currencies_code_looks_like_a_code CHECK (code ~ '^[A-Z0-9]{3,12}$')
);

-- Seed rows: the currencies this project's worked examples use (INR, JPY for the cross-currency cases, USD
-- for the CBDC comparison in the plan). An insert, never a release - the same rule V201 states for tax
-- jurisdictions - so adding a currency to a deployment does not wait for a build.
--
-- `USD` is not marked as a CBDC and has no jurisdiction: the product's CBDC is a jurisdiction's ledger
-- currency, and which code that is has not been decided. Stated rather than filled in with something
-- plausible, because a wrong `is_cbdc` decides how a real transfer is addressed.
INSERT INTO currencies (code, minor_unit_exponent, is_cbdc, jurisdiction) VALUES
    ('INR', 2, false, 'IN'),
    ('JPY', 0, false, 'JP'),
    ('USD', 2, false, NULL);
