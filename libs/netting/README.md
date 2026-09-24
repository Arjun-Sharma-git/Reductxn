# libs/netting

**Purpose:** the product differentiator. Collapses a group position into the minimum-transfer
settlement, and computes the tax on it. Also owns the `TaxRule` engine.

**Public surface:** `io.reductxn.netting.api.*` - `NettingEngine`, `NettingRequest`, `NettingResult`,
`NetPosition`, `TransferInstruction`. Everything else is `internal` (RULE B10/B11).

**May depend on:** `libs/kernel`, `libs/ledger` (RULE B3).

**Must NOT depend on:** `libs/rails`, Spring, JDBC, HTTP.

**Guarantee:** greedy baseline is always correct and returns at most N-1 transfers for N members
with non-zero net position. The optimizer improves on it when it can prove a better result, and
never regresses the baseline.

**Spec:** `docs/specs/netting.md`, `docs/specs/tax-rules.md`. **Owner:** ledger reviewer. **Review bar:** two reviewers.
