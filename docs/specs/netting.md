# Spec - Netting engine

**Owner:** ledger reviewer · **Module:** `libs/netting` · **Review bar:** two reviewers

## 1. Problem

Given a group's obligations within a cycle, produce a set of real transfers that settles every
member's position, minimising the objective implied by the active tax rule.

## 2. Inputs and outputs

```
NettingRequest(Money currency, List<MemberId> members, List<Obligation> obligations,
               TaxRule taxRule, NettingPolicy policy)

NettingResult(List<NetPosition> positions, List<TransferInstruction> transfers,
              Money grossMinor, Money netMinor, Money taxMinor, Money taxSavedMinor,
              Algorithm algorithm, boolean provablyOptimal)
```

## 3. Algorithm - two tiers

**Tier 1, correctness baseline (always runs).**

1. `balance[i] = sum(credits[i]) - sum(debits[i])`. By construction `sum(balance) == 0`.
2. Repeatedly match the largest debtor with the largest creditor; transfer
   `min(-balance[debtor], balance[creditor])`.
3. Yields at most `N-1` transfers for `N` members with non-zero balance.

Complexity `O(N log N)` with a heap. Always terminates. Always valid.

**Tier 2, opportunistic optimizer.**

1. Partition members into zero-sum subsets (meet-in-the-middle for small N, MIP via Timefold or
   OR-Tools beyond that). Members whose subset sums to zero settle internally with **zero**
   transfers, which is the global optimum for that subset.
2. Run Tier 1 on the residue.
3. Accept only if it **strictly improves** on the Tier 1 result; otherwise discard.

The optimizer is an upgrade, never a dependency. Timeout or failure degrades silently to Tier 1.

## 4. Properties (jqwik, must hold for all generated inputs)

| # | Property |
|---|---|
| N1 | `sum(positions.netMinor) == 0` |
| N2 | Applying all transfers zeroes every member's balance |
| N3 | No member ends negative |
| N4 | `transfers.size() <= N - 1` (Tier 1 bound) |
| N5 | Optimizer result is never worse than baseline (count and, where relevant, tax) |
| N6 | `grossMinor - netMinor == sum of netted-away amounts` |
| N7 | Determinism: identical input yields identical output |
| N8 | Tax is non-negative, and zero base implies zero tax |

## 5. Worked example

10 members, 23 obligations, total gross 100,000 minor units. Tier 1 produces 7 transfers.
Tier 2 finds one zero-sum subset of 4 members and reduces the count to 5. Tax saving is reported
as the delta between the pairwise-tax and netted-tax figures.

## 6. Open questions

- Whether to expose transfer-size constraints (some rails cap a single transfer).
- Whether optimality should be proven and stored per run, as an audit artefact.
- Multi-currency groups: net per currency, no FX in v1.

---

## 7. Known defect: netting can cost more than pairwise (ADR 0011)

The first full build of the property suite disproved a core assumption. Greedy netting is **not**
always cheaper than settling the obligations directly.

Measured counterexample, reproduced directly against the compiled engine:

```
3 obligations across 5 members:  A -> B 300,  A -> C 100,  D -> E 200
pairwise settlement : 3 transfers, 150 tax
greedy netting      : 4 transfers, 200 tax
taxSaved            : -50
```

Netting removes transfers only when the obligation set is dense relative to the member count. A
sparse set has nothing to remove, and re-matching unrelated members can invent transfers, so under a
flat per-transfer tax the group is charged more.

The bound in N4 (`transfers <= members - 1`) still holds. It is simply not the binding constraint.
The relevant comparison is against the number of obligations, for which no bound exists.

**Consequence for this spec:** properties N1-N7 remain valid. `taxSaved >= 0` is **not** valid today
and must not be asserted or displayed until the pairwise baseline in ADR 0011 is implemented.

**Required behaviour once fixed:** compute the pairwise plan and the netted plan, return the cheaper
one, and report `taxSaved = max(0, pairwiseTax - chosenTax)`.
