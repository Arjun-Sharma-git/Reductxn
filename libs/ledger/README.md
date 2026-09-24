# libs/ledger

**Purpose:** the double-entry ledger model - `Money` (integer minor units + currency), `JournalEntry`,
`Posting`, accounts, and the invariants that make the ledger trustworthy.

**Public surface:** `io.reductxn.ledger.api.*`.

**May depend on:** `libs/kernel` (RULE B2).

**Must NOT depend on:** `libs/netting`, `libs/rails`, Spring, JDBC, HTTP.

**Invariants (enforced by jqwik property tests):**

- Every journal entry balances to zero per currency.
- Obligations never mutate; corrections are compensating entries.
- Append-only: no delete path exists in code, and the DB role has no DELETE grant.

**Spec:** `docs/specs/ledger.md`. **Owner:** ledger reviewer. **Review bar:** two reviewers.
