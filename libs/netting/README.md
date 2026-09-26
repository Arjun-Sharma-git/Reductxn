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

**Owns the `tax_rules` table** (RULE B17), which is why the migration is here rather than in a deployable:
`src/main/resources/db/migration/V201__tax_rules.sql`, in the `2xx` version range. Reading it is somebody
else's job - `apps/api` has a read model over it - and this module stays framework-free, so the file is data
about a rule rather than code that loads one.

**Nothing here executes a `threshold` rule.** The spec and ADR 0006 list three shapes and this module
implements flat, ad valorem and a no-tax rule. The gap is refused loudly where rules are read, rather than
silently assessing zero.

