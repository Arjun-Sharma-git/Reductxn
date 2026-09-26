-- The obligation's locked quote, for a commitment whose creditor is paid in another currency.
--
-- One column, and small because the row it points at is self-contained. `libs/fx`'s `fx_quotes` holds the
-- rate, both amounts, both exponents and the residual (V402), so nothing about what the creditor was promised
-- is repeated here - and the settlement currency is not a column either, because it is the quote's quote
-- currency. One fact, one place: the alternative is a settlement currency that can disagree with the quote it
-- belongs to.
--
-- No foreign key, for the reason V101 gives for `group_id`: `fx_quotes` is `libs/fx`'s table and this is
-- `apps/api`'s (RULE B17), so a constraint across them would make one module's migrations a prerequisite of
-- the other's. The read path joins them anyway, in the adapter that builds an obligation.

ALTER TABLE obligations ADD COLUMN quote_id uuid;

-- A single-currency obligation must not have a quote, and a cross-currency one must. The second half cannot
-- be checked without reading `fx_quotes`, so only the first is here; the second is the use case's, where it
-- is a decision rather than a constraint.
--
-- A cross-currency obligation is also *unrepresentable* without its quote, which is the point of this
-- migration: before the column existed, recording one would have stored its debt side only and the netting
-- preview would have read it back as a single-currency obligation, silently, in the currency the creditor is
-- not being paid in. That landmine was written down in `RecordObligationService` and this is the slice that
-- removes it.

-- Finding them is rare (a void, an audit), so a partial index over the small set that has one.
CREATE INDEX obligations_with_a_quote ON obligations (quote_id) WHERE quote_id IS NOT NULL;
