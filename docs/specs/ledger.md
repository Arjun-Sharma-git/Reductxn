# Spec - Double-entry ledger

**Owner:** ledger reviewer · **Module:** `libs/ledger` · **Review bar:** two reviewers

## 1. Model

Accounts are typed by `owner_type` (`user`, `group`, `fee`, `tax`, `fx`) and `kind` (asset,
liability, income). Every state change is a `JournalEntry` containing two or more `Posting` rows.

```
journal_entries(id, group_id, currency, occurred_at, kind, created_by, idempotency_key,
                fx_link_id, fx_role[base|quote], rail_performed_fx, prev_hash, hash)
postings(id, entry_id, account_id, direction[D|C], amount_minor, currency)
```

`kind` in { obligation, settlement, fee, tax, fx, adjustment }.

`owner_type = 'fx'` is the **FX clearing account**. It is a real account, excluded from member netting
but included in the group balance report, and it must be zero per currency at the end of a cycle
(ADR 0012, `docs/specs/fx.md`).

## 2. Invariants

| # | Invariant | Verified by |
|---|---|---|
| L1 | Per entry, total debits equal total credits **per currency** | DB constraint + jqwik |
| L2 | `amount_minor` is strictly positive; direction carries the sign | DB check constraint |
| L3 | Entries are immutable; corrections are compensating entries | DB grants (no UPDATE/DELETE) |
| L4 | `hash = H(prev_hash, canonical(entry, postings))` | hash-chain verifier job |
| L5 | A balance is a projection over postings, never a stored truth | architecture review |
| L6 | Currency of all postings in an entry is identical | application + constraint |
| L7 | Conversion conserves value: the rounding residual is booked to `fx_clearing`, never dropped | jqwik X2 |
| L8 | `fx_clearing` is zero, per currency, at the end of every cycle - or the run is flagged `externally_funded` | settlement pre-check |
| L9 | FX linkage: every entry with `fx_role = base` has exactly one partner with the same `fx_link_id`, `fx_role = quote`, in the same group | cross-chain verifier job |

L6 is deliberately **unchanged** by multicurrency. A cross-currency obligation becomes two linked
single-currency entries rather than one mixed entry, which is what keeps L1 and L6 expressible as DB
constraints (ADR 0012).

L9 exists because a valid hash chain in one currency does **not** prove that its partner entry exists
or matches. The linkage has to be verified across chains, not within one.

**The `Verified by` column names where verification belongs, not what is built.** Status lives in
MASTER-PLAN section 15, and each invariant that is not yet asserted is listed in the deferral map in
`PropertyCoverageTest` with the mechanism it waits on. Keeping status out of this table is deliberate: a
spec that reports progress stops being a specification, and the two then disagree.

## 3. Hash chain

Entries are chained per `(group_id, currency)`. Chain verification is an offline job that can run
without the application, which is what makes the ledger independently auditable.

A second verification pass covers **cross-chain FX linkage** (L9): a chain being internally valid
does not prove that the partner entry in the other currency exists, or that the pair matches the
stored quote. Both passes are required.

## 4. Append-only is enforced below the application

The application's database role is granted `INSERT` and `SELECT` only on `journal_entries` and
`postings`. No code path can contradict the invariant, because the database refuses.

## 5. Money representation

`Money` is a record of `(long amountMinor, Currency currency)`. There is no `double` or `float`
anywhere in the module; a build rule fails on their appearance. Currency carries its minor-unit
exponent, so formatting never guesses.

**Rates are not money** and live in `libs/fx` as `(long rateScaled, int rateScale)` plus the currency
pair (ADR 0013). `libs/ledger` does not multiply by a rate and does not know what a rate is; it only
stores the two balanced entries that a conversion produced. Nothing outside `libs/fx` may multiply an
amount by a rate (RULE B19).

## 6. Open questions

- Confirmation depth for the ledger hash chain (verify hourly, or on demand?).
- Whether `fee`, `tax` and `fx` accounts are group-scoped or global.
- Retention and archival policy for old partitions.
- Whether the residual accumulated on `fx_clearing` may be allocated pro rata to members instead of
  accumulating (ADR 0013, open question).
