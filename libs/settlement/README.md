# libs/settlement

Multicurrency composition: turn a cycle of obligations into **per-currency settlement plans**. Pure
Java: no Spring, no JDBC, no HTTP, no clock, no provider (RULE B5, P4).

## Purpose

ADR 0012 decided that netting stays single-currency: a multicurrency cycle is partitioned by currency
and each partition is handed to the existing engine unchanged. Something has to do that partitioning
and compose the results. That is this module.

It also owns the only model of the `fx_clearing` account, and derives the group's **external FX
requirement** from it - the honest consequence of P2, since reductxn holds no currency and therefore
cannot manufacture one.

## Why this module exists at all

RULE B6: `apps/api` and `workers/settlement` may not depend on each other, so logic needed by both
moves *down* into `libs/*`. The API needs the plan to preview a cycle; the worker needs it to build
the run.

It could not live in `libs/netting`. That module may only depend on kernel and ledger (B3), and giving
the netting engine FX awareness is exactly what ADR 0012 forbids - its properties depend on one
currency at a time. So the composition is its own pure module, below both deployables and above the
engine.

| Module | Owns |
|---|---|
| `libs/settlement` | **What** must be settled: partitions, positions, transfers, the clearing position, the external requirement |
| `workers/settlement` | **Executing** it: run lifecycle, quorum, idempotency, rail submission, funding state |

## Public surface

Everything callers may touch lives in `io.reductxn.settlement.api` (RULE B10).

| Type | Role |
|---|---|
| `CycleObligation` | A commitment, either single- or cross-currency (sealed, so the two cases are exhaustive) |
| `SingleCurrencyObligation` / `CrossCurrencyObligation` | The two cases. A cross-currency one carries its locked `FxQuote` |
| `CurrencyPlan` | One currency's plan: the netting result, the clearing position, member vs clearing transfers |
| `SettlementPlan` | All partitions, per currency, ordered deterministically |
| `SettlementPlanner` / `SettlementPlans` | The operation, reached through `SettlementPlans.perCurrency()` |
| `TaxRuleResolver` | Port for resolving the tax rule per currency partition |
| `FxClearing` | The reserved internal account id, and the check that it is never a member |

`io.reductxn.settlement.internal` is private and must not be referenced from outside this module.

## Allowed dependencies

`libs/kernel`, `libs/ledger`, `libs/netting`, `libs/fx` (RULE B21). Nothing else.

## What this module does NOT do

- It does **not** convert anything. Conversion happens when an obligation is recorded (ADR 0012), and
  only `libs/fx` does it (B19). By the time an obligation reaches here it already carries its locked
  quote.
- It does **not** read a clock, a rate provider, or a database. The same cycle always produces the
  same plan, which is what makes a preview trustworthy and an audit reproducible.
- It does **not** execute anything, hold state, or know what a rail is.
- It does **not** book anything to the ledger, and it does not consume residual fractions. A residual
  is sub-minor and cannot be transferred; booking it is the ledger's job.

## The two things worth knowing before changing anything here

1. **A cross-currency obligation becomes two legs in two partitions**, one per book, with `fx_clearing`
   as the counterparty (ADR 0012 point 3). The JPY leg uses the debt currency at the **owed** amount;
   the other uses the settlement currency at the **converted** amount.
2. **`fx_clearing` is netted like any other participant, then reported separately.** It has to be
   netted, or the transfers come out wrong. It has to be reported separately, or the group's FX
   exposure is presented as though it were a member's debt.
3. **The legs are sorted before the engine sees them, and that is load-bearing.** The netting engine is
   deterministic only for *identical* input: its pairwise tier emits one transfer per obligation in the
   order they arrived, which is asserted as intended behaviour in `libs/netting`. A cycle's obligations
   are a set that comes out of a database, so without normalising here the same cycle could produce two
   plans equal in every amount and unequal as values - meaning an approved preview was not literally the
   plan that executed. Normalising here rather than in the engine is what keeps ADR 0012's "the engine
   does not change" true.

## Tests

`./gradlew :libs:settlement:test`

Unit tests use the ADR 0012 worked example. The property tests assert that **N1-N9 hold per currency**
- the claim ADR 0012 makes - plus X7 (per-currency zero-sum including `fx_clearing`), that partitions
are complete and disjoint, and that the plan is deterministic.
