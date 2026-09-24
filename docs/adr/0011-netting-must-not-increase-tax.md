# ADR 0011 - Netting must not increase the settlement tax

- **Status:** proposed
- **Date:** 2026-09-24
- **Related:** principles P10; ADR 0005, ADR 0006; RULEs B3, B10

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

**Proposed.** Not yet implemented. The engine must guarantee that the plan it returns is never more
expensive than settling the obligations directly:

1. Compute the **pairwise baseline** - one transfer per obligation, with its tax.
2. Compute the **netted plan** - greedy, and any optimiser tier available.
3. Return whichever plan has the **lower total tax**. On a tie, prefer the plan with fewer
   transfers, then the netted plan.
4. `taxSaved` becomes `max(0, pairwiseTax - chosenTax)` and is therefore never negative.
5. `NettingResult.Algorithm` gains a value for the baseline case, so a run records which plan was
   chosen and why. This is a contract change: `contracts/openapi/reductxn-api.yaml` must be updated
   **first** (RULE C1), then the client regenerated (RULE C3).

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

## Open question

For **ad valorem** tax the objective is volume, not count, and the pairwise baseline has higher
volume by construction, so the defect may not arise there. The guard should be expressed in terms of
the active tax rule's own `assess` function rather than a transfer count, so it is correct for every
rule shape. Verify before implementing.
