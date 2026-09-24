# Spec - Double-entry ledger

**Owner:** ledger reviewer · **Module:** `libs/ledger` · **Review bar:** two reviewers

## 1. Model

Accounts are typed by `owner_type` (`user`, `group`, `fee`, `tax`) and `kind` (asset, liability,
income). Every state change is a `JournalEntry` containing two or more `Posting` rows.

```
journal_entries(id, group_id, occurred_at, kind, created_by, idempotency_key, prev_hash, hash)
postings(id, entry_id, account_id, direction[D|C], amount_minor, currency)
```

`kind` in { obligation, settlement, fee, tax, adjustment }.

## 2. Invariants

| # | Invariant | Verified by |
|---|---|---|
| L1 | Per entry, total debits equal total credits **per currency** | DB constraint + jqwik |
| L2 | `amount_minor` is strictly positive; direction carries the sign | DB check constraint |
| L3 | Entries are immutable; corrections are compensating entries | DB grants (no UPDATE/DELETE) |
| L4 | `hash = H(prev_hash, canonical(entry, postings))` | hash-chain verifier job |
| L5 | A balance is a projection over postings, never a stored truth | architecture review |
| L6 | Currency of all postings in an entry is identical | application + constraint |

## 3. Hash chain

Entries are chained per `(group_id, currency)`. Chain verification is an offline job that can run
without the application, which is what makes the ledger independently auditable.

## 4. Append-only is enforced below the application

The application's database role is granted `INSERT` and `SELECT` only on `journal_entries` and
`postings`. No code path can contradict the invariant, because the database refuses.

## 5. Money representation

`Money` is a record of `(long amountMinor, Currency currency)`. There is no `double` or `float`
anywhere in the module; a build rule fails on their appearance. Currency carries its minor-unit
exponent, so formatting never guesses.

## 6. Open questions

- Confirmation depth for the ledger hash chain (verify hourly, or on demand?).
- Whether `fee` and `tax` accounts are group-scoped or global.
- Retention and archival policy for old partitions.
