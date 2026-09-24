# ADR 0011 - Netting must not increase the settlement tax

- **Status:** accepted
- **Date:** 2026-09-24
- **Implemented:** `GreedyNetting`, branch `fix/netting-pairwise-baseline`
- **Related:** principles P10; ADR 0005, ADR 0006; RULEs B3, B10, C1

## Context

reductxn's entire value proposition is that netting reduces the CBDC transaction tax. The greedy
engine (ADR 0005, tier 1) was assumed to always be at least as cheap as settling the original
obligations directly, so `taxSaved` was computed as `taxIfPairwise - taxOnNetted` and assumed
non-negative.

The jqwik property suite disproved that assumption on the first full Gradle build. The counterexample
is small and was reproduced directly:

```
3 obligations across 5 members:
  A -> B  300
  A -> C  100
  D -> E  200

pairwise settlement : 3 transfers, 150 tax (flat 50 per transfer)
greedy netting      : 4 transfers, 200 tax
taxSaved            : -50
```

Greedy netting makes the group **worse off by 33 percent**.

## Root cause

Greedy netting operates on **net positions**, and always produces a complete matching of those
positions. It has no knowledge of the original obligation set, so it cannot notice that the
obligations were already a cheaper plan.

Netting removes transfers only when the obligation set is **dense** relative to the member count:
with many obligations among few members, positions are large and few and one transfer retires a lot
of obligation. When the set is **sparse** - few obligations spread across many members, so each
member appears once - there is nothing to remove, and re-matching unrelated members can invent
transfers that did not need to exist.

The bound `transfers <= members - 1` (property N4) is not the relevant bound here. It is satisfied.
The relevant comparison is against the number of obligations, and no such bound exists.

## Decision

The engine must guarantee that the plan it returns is never more expensive than settling the
obligations directly:

1. Compute the **pairwise baseline** - one transfer per obligation, with its tax.
2. Compute the **netted plan** - greedy, and any optimiser tier available.
3. Return whichever plan has the **lower total tax**. On a tie, prefer the plan with fewer
   transfers, then the netted plan.
4. `taxSaved` becomes `max(0, pairwiseTax - chosenTax)` and is therefore never negative.
5. `NettingResult.Algorithm` gains a value for the baseline case, so a run records which plan was
   chosen and why. This is a contract change: `contracts/openapi/reductxn-api.yaml` must be updated
   **first** (RULE C1), then the client regenerated (RULE C3).

### As implemented

- `contracts/openapi/reductxn-api.yaml` was updated first, adding `pairwise` to the algorithm enum
  (RULE C1). Client regeneration follows when a client exists to regenerate.
- `NettingResult.Algorithm.PAIRWISE` records that the direct plan was chosen.
- **Tie-breaking:** lower tax wins; on equal tax the plan with fewer transfers wins, because fewer
  moves is less operational risk for the same money; a genuine tie keeps the netted plan.
- **Zero-amount obligations** produce no transfer in either plan, because nothing needs to move and
  there is nothing to tax. Without this the direct plan would emit a zero transfer, which the
  instruction record rejects.
- Measured against the compiled engine after the change:

  ```
  dense  (4 obligations, 4 members):  GREEDY    chosen, 3 transfers, tax 150, saved 50
  sparse (3 obligations, 5 members):  PAIRWISE  chosen, 3 transfers, tax 150, saved  0
  ```

  The sparse case previously chose the netted plan and reported minus 50.
- Properties N8 (tax and the reported saving are never negative) and N9 (the engine never costs more
  than settling directly) now hold and are asserted by the property suite. N9 is the invariant that
  makes the product's claim true rather than aspirational.

### On ad valorem

The guard is expressed through the active rule's own `assess` function, not a transfer count, so it
is correct for every rule shape. Under pure ad valorem the direct plan carries higher volume by
construction, so the netted plan normally wins and this guard rarely fires. It still earns its place:
a per-transfer minimum charge makes the direct plan's tax scale with the transfer count, and that is
exactly the regime where netting has to prove it is better.

## Consequences

- **Easier:** the headline claim becomes true and provable. `taxSaved >= 0` becomes an invariant the
  property suite can assert, instead of a hope. Groups with sparse activity are correctly served
  rather than quietly overcharged.
- **Harder:** the engine now returns plans of two different shapes - netted transfers and direct
  obligations - so callers must present both sensibly. A settlement run may legitimately contain no
  netting at all, and the UI must say so rather than show an empty "savings" panel.
- The `Algorithm` enum grows, which is a wire-contract change and therefore a versioning decision.

## Alternatives considered

- **Always net, but report a negative saving.** Rejected: displaying a negative "you saved" number
  to a group is worse than useless, and it invites a dispute.
- **Suppress the saving when negative and say nothing.** Rejected: hides a real cost difference the
  group should see, and the UI would imply netting always helps.
- **Fix greedy to consider the obligation set.** Rejected as the primary fix: greedy would need to
  reason about both plans anyway, so having it compare two complete plans is simpler and testable.
  It also generalises to the optimiser tier and to ad valorem rules.
- **Only allow netting when obligations outnumber members.** Rejected: too crude. A dense group can
  still contain a sparse subset worth settling directly.

## Effect on the property suite

N1-N7 are unaffected. N8 changed meaning: it previously asserted that `taxSaved` was exactly the
difference between the two plans, and it now also asserts that neither the tax nor the reported
saving can be negative. N9 is new, and it is the property that holds this decision in place.
