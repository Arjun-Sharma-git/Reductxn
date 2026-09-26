-- Tax rules: tax law as data, resolved by (jurisdiction, currency) and effective date (ADR 0006,
-- docs/specs/tax-rules.md section 1).
--
-- Owned by libs/netting, which is why this file lives beside it and not in platform/persistence: that
-- module runs migrations, it does not own tables (RULE B17). The owning module is the one that knows what
-- a tax rule is, which is the same reason it holds the TaxRule contract.
--
-- `V201`, not `V1`: Flyway keeps one version sequence across every location it reads, so a version has to
-- identify its owner as well as its order. libs/netting owns 2xx (see V101 in apps/api for the scheme).
--
-- Adding a jurisdiction is an insert, never a release.

CREATE TABLE tax_rules (
    id             uuid        PRIMARY KEY,

    -- Resolution key. `currency` rather than a currency id: `currencies` belongs to libs/fx and a rule is
    -- selected by code.
    jurisdiction   text        NOT NULL,
    currency_code  text        NOT NULL,

    -- The shapes docs/specs/tax-rules.md section 2 defines. A constraint rather than a convention,
    -- because a kind the engine cannot execute is a rule that silently does not apply.
    kind           text        NOT NULL,

    -- Shape-specific parameters: { perTransferMinor } for flat, { rateBps, minMinor?, maxMinor? } for
    -- advalorem, { tiers[], fixedMinor? } for threshold.
    params_json    jsonb       NOT NULL,

    effective_from timestamptz NOT NULL,
    effective_to   timestamptz,

    -- Which statute this encodes. Required, so a rule can be defended to an auditor rather than only
    -- executed.
    source_ref     text        NOT NULL,

    CONSTRAINT tax_rules_kind_is_known CHECK (kind IN ('flat', 'advalorem', 'threshold')),
    CONSTRAINT tax_rules_effective_window CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT tax_rules_params_is_an_object CHECK (jsonb_typeof(params_json) = 'object')
);

-- Exactly one rule must apply to a (jurisdiction, currency) at an instant. This index cannot enforce
-- that - overlap is a range-exclusion problem - but it makes the lookup the index's only job.
CREATE INDEX tax_rules_active
    ON tax_rules (jurisdiction, currency_code, effective_from DESC);
