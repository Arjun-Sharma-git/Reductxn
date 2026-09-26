-- Rates and quotes: market data, and the locked application of it to one amount (docs/specs/fx.md section 1).
--
-- Owned by libs/fx, which is why this file sits beside it (RULE B17). `V402`: libs/fx owns 4xx; `V401` is
-- `currencies`, which is here because the exponent is what conversion needs first.
--
-- **Two tables with overlapping columns, on purpose.** A rate is market data and is superseded; a quote is a
-- locked conversion and is contractual. The quote therefore *embeds* the rate it was locked against rather
-- than pointing at a row in `fx_rates`, and that is the correction this migration makes to the sketch in
-- docs/specs/fx.md section 1, which had a `rate_id`. A pointer would make history depend on reference data
-- that is expected to change: superseding a rate would either break the foreign key or silently restate what
-- a creditor was promised (ADR 0013 point 5). The quote is self-contained so that reading a past obligation
-- needs nothing but the obligation.
--
-- Exponents travel with every amount, as they do in `obligations` and `journal_entries`: `FxRate` carries a
-- `Currency`, not a code, and a stored rate whose meaning depended on today's catalogue could change meaning
-- without anyone editing it.

CREATE TABLE fx_rates (
    id                uuid        PRIMARY KEY,

    -- The pair. A rate against itself cannot exist (FxRate refuses it), and neither can one here.
    base_code         text        NOT NULL,
    base_exponent     smallint    NOT NULL,
    quote_code        text        NOT NULL,
    quote_exponent    smallint    NOT NULL,

    -- `rateScaled / 10^rateScale`, exactly (P12). Never a floating-point value.
    rate_scaled       bigint      NOT NULL,
    rate_scale        smallint    NOT NULL,

    -- The provider's mid rate at the same scale, and the spread applied. Both are stored: only the applied
    -- rate would hide the spread from the group, and only the mid would make it impossible to tell whether a
    -- spread had been applied at all.
    mid_rate_scaled   bigint      NOT NULL,
    spread_bps        integer     NOT NULL,

    -- Provider identifier, required so a rate can be defended to an auditor rather than only executed.
    source            text        NOT NULL,

    quoted_at         timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,

    CONSTRAINT fx_rates_pair_is_two_currencies CHECK (base_code <> quote_code),
    CONSTRAINT fx_rates_rate_is_positive CHECK (rate_scaled > 0),
    CONSTRAINT fx_rates_mid_is_positive CHECK (mid_rate_scaled > 0),
    CONSTRAINT fx_rates_spread_is_not_negative CHECK (spread_bps >= 0),
    CONSTRAINT fx_rates_scale_in_range CHECK (rate_scale BETWEEN 0 AND 8),
    CONSTRAINT fx_rates_exponents_in_range
        CHECK (base_exponent BETWEEN 0 AND 8 AND quote_exponent BETWEEN 0 AND 8),
    -- A rate with no expiry has an unknown age, and FxRateProvider's contract forbids using one.
    CONSTRAINT fx_rates_expiry_follows_the_quote CHECK (expires_at > quoted_at),
    CONSTRAINT fx_rates_source_is_named CHECK (btrim(source) <> '')
);

-- The provider's only query: the most recent rate for a pair as of an instant. Descending on `quoted_at`,
-- because "most recent" is the whole selection rule.
CREATE INDEX fx_rates_pair_by_recency
    ON fx_rates (base_code, quote_code, quoted_at DESC);

CREATE TABLE fx_quotes (
    id                     uuid        PRIMARY KEY,

    -- What was locked: the base amount and the converted result, with both exponents.
    base_code              text        NOT NULL,
    base_exponent          smallint    NOT NULL,
    base_amount_minor      bigint      NOT NULL,

    quote_code             text        NOT NULL,
    quote_exponent         smallint    NOT NULL,
    converted_amount_minor bigint      NOT NULL,

    -- The rounding residual, kept fractional rather than rounded to a minor unit: 0.096 of a rupee has no
    -- representation as an integer, and rounding it would invent or destroy value on every conversion
    -- (ADR 0013 point 3, FxConversion). 2 * 8 is FxConversion.MAX_RESIDUAL_SCALE.
    residual_scaled        bigint      NOT NULL,
    residual_scale         smallint    NOT NULL,

    -- The rate, embedded. See the note at the top of this file.
    rate_scaled            bigint      NOT NULL,
    rate_scale             smallint    NOT NULL,
    mid_rate_scaled        bigint      NOT NULL,
    spread_bps             integer     NOT NULL,
    rate_source            text        NOT NULL,
    rate_quoted_at         timestamptz NOT NULL,
    rate_expires_at        timestamptz NOT NULL,

    locked_at              timestamptz NOT NULL,

    -- True when the rail performed the conversion rather than the group funding it (ADR 0013 point 8).
    rail_performed_fx      boolean     NOT NULL,

    CONSTRAINT fx_quotes_pair_is_two_currencies CHECK (base_code <> quote_code),
    CONSTRAINT fx_quotes_base_is_a_magnitude CHECK (base_amount_minor >= 0),
    CONSTRAINT fx_quotes_converted_is_a_magnitude CHECK (converted_amount_minor >= 0),
    CONSTRAINT fx_quotes_residual_scale_in_range CHECK (residual_scale BETWEEN 0 AND 16),
    CONSTRAINT fx_quotes_rate_is_positive CHECK (rate_scaled > 0),
    CONSTRAINT fx_quotes_scale_in_range CHECK (rate_scale BETWEEN 0 AND 8),
    CONSTRAINT fx_quotes_mid_is_positive CHECK (mid_rate_scaled > 0),
    CONSTRAINT fx_quotes_spread_is_not_negative CHECK (spread_bps >= 0),
    CONSTRAINT fx_quotes_exponents_in_range
        CHECK (base_exponent BETWEEN 0 AND 8 AND quote_exponent BETWEEN 0 AND 8),
    -- The embedded rate has to have been a rate: quoted before it expired, and before the conversion was
    -- locked. Checked here as well as in FxRate, because this row is what a reader trusts years later.
    CONSTRAINT fx_quotes_rate_window_is_a_window CHECK (rate_expires_at > rate_quoted_at),
    CONSTRAINT fx_quotes_locked_after_the_quote CHECK (locked_at >= rate_quoted_at)
);

-- An obligation points at its quote (`obligations.quote_id`, in apps/api), so this is how a reader finds the
-- conversions behind a group's obligations. Deliberately not a foreign key in either direction: the two tables
-- are owned by different modules (RULE B17), and a cross-module foreign key would make one module's history a
-- prerequisite of the other's migrations.
CREATE INDEX fx_quotes_by_pair ON fx_quotes (base_code, quote_code, locked_at DESC);
