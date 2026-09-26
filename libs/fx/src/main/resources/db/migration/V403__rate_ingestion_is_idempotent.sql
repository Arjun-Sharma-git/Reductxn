-- Rate ingestion: a provider's publication is stored once, and storing it again is a no-op.
--
-- `V403` continues libs/fx's `4xx` range (V401 currencies, V402 rates and quotes).
--
-- **Why the rule is here rather than in the loader.** The loader is a scheduled job, which means it runs again
-- after a restart, a second instance may overlap it, and an operator can run it by hand. Each of those can
-- present the same publication twice, and the two honest outcomes are "this rate is already recorded" and
-- "record it again" - where the second is unbounded duplication of market data that every reader then resolves
-- by recency, so the table grows with every restart while every query keeps returning the same answer. Making
-- it the database's rule lets the loader be naive (`ON CONFLICT DO NOTHING`) and still be correct, which is
-- the arrangement that does not need the loader to remember anything.
--
-- `source` is part of the key, so two providers quoting the same pair at the same instant are two facts rather
-- than a conflict - the shape a failover provider needs (docs/specs/fx.md section 11).
--
-- `quoted_at` is part of the key rather than only a column: a provider that publishes again at a different
-- instant (a new working day, a correction) is publishing a *second* rate, and superseding is already what
-- that column means to every reader - `JooqFxRateProvider` takes the most recent rate at or before an instant.
ALTER TABLE fx_rates ADD CONSTRAINT fx_rates_publication_is_unique
    UNIQUE (base_code, quote_code, source, quoted_at);
