# Spec - Netting engine

**Owner:** ledger reviewer · **Module:** `libs/netting` · **Review bar:** two reviewers

## 1. Problem

Given a group's obligations within a cycle, produce a set of real transfers that settles every
member's position, minimising the objective implied by the active tax rule.

## 2. Inputs and outputs

The engine is **single-currency by construction**. This is a deliberate constraint, not a limitation
to be lifted: it is what makes N1-N9 provable. Multicurrency is handled outside the engine (section 8).

```
NettingRequest(Currency currency, List<Obligation> obligations, TaxRule taxRule)
    -- validates that every obligation shares `currency`, else CurrencyMismatchException

NettingResult(Money gross, Money net, Money tax, Money taxSaved, int grossTransferCount,
              List<NetPosition> positions, List<TransferInstruction> transfers,
              Algorithm algorithm, boolean provablyOptimal)

Algorithm in { GREEDY, SUBSET_PARTITION, MIP, PAIRWISE }
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

Every property above except **N5** is asserted by `GreedyNettingPropertyTest`, and each assertion is
labelled with its own id (`.as("N4: ...")`) so the coverage is machine-checkable rather than a claim in
a document. `PropertyCoverageTest` fails the build if a property here has neither a labelled assertion
nor a recorded reason.

**N5 is recorded as deferred, and the reason is that there is nothing to compare against.** It compares
the optimizer's result to the baseline, and Tier 2 (section 3) does not exist yet. It said nothing
before, which is the problem: an untested property and an obeyed one look exactly alike, so N5 sat
unasserted through every build while the table implied otherwise. The day an optimizer lands, N5 becomes
the first thing to test rather than a line nobody re-reads.

## 5. Worked example

10 members, 23 obligations, total gross 100,000 minor units. Tier 1 produces 7 transfers.
Tier 2 finds one zero-sum subset of 4 members and reduces the count to 5. Tax saving is reported
as the delta between the pairwise-tax and netted-tax figures.

## 6. Open questions

- Whether to expose transfer-size constraints (some rails cap a single transfer).
- Whether optimality should be proven and stored per run, as an audit artefact.
- Whether the pairwise baseline (section 7) should also be applied per currency partition, and whether
  a cross-currency cycle should compare currency strategies as well as plans (ADR 0012, open question).

---

## 7. The pairwise baseline (ADR 0011)

Greedy netting is **not** always cheaper than settling the obligations directly. The property suite
found this on the first full build: three obligations across five members needed 4 transfers when
netted against 3 when settled directly, so a flat per-transfer tax rose from 150 to 200.

Netting removes transfers only when the obligation set is dense relative to the member count. A
sparse set has nothing to remove, and re-matching unrelated members can invent transfers.

The N4 bound (`transfers <= members - 1`) still holds for the netted plan; it never was the binding
constraint here. The relevant comparison is against the number of obligations, for which no bound
exists.

**Required behaviour, now implemented:** build both plans, return the cheaper one, and report
`taxSaved = pairwiseTax - chosenTax`, which therefore cannot be negative. Tie-breaking is by tax
first, then by transfer count, then the netted plan wins. A result carrying
`Algorithm.PAIRWISE` means no saving was available and `taxSaved` is zero.

Measured on the compiled engine:

```
dense  (4 obligations, 4 members):  GREEDY    chosen, 3 transfers, tax 150, saved 50
sparse (3 obligations, 5 members):  PAIRWISE  chosen, 3 transfers, tax 150, saved  0
```

Properties N8 (`taxSaved >= 0`) and N9 (the engine never costs more than settling directly) assert
this. N9 is what makes the product's central claim true rather than aspirational.

---

## 8. Multicurrency (ADR 0012)

**The engine does not become multicurrency.** A multicurrency cycle is **partitioned by currency**,
and each partition is handed to this engine unchanged. Every property in section 4 therefore holds
**per currency** rather than globally, and no algorithm changes:

```
cycle for group G            (libs/planning - see MASTER-PLAN rule B21)
  |-- partition obligations by settlement currency
  |     JPY: [ ... ]  ->  NettingRequest(JPY, ...)  ->  N1..N9 hold within JPY
  |     RUB: [ ... ]  ->  NettingRequest(RUB, ...)  ->  N1..N9 hold within RUB
  |     INR: [ ... ]  ->  NettingRequest(INR, ...)  ->  N1..N9 hold within INR
  `-- merge results; totals are reported per currency, never summed in bare minor units
```

The partitioner lives in `libs/planning`, not here. It cannot live here: this module may only depend
on kernel and ledger (B3), and partitioning needs to understand a locked FX quote. Netting stays
unaware of FX on purpose, because every property above assumes one currency at a time.

**A cross-currency obligation becomes two obligations, in two partitions.** ADR 0012 point 3 requires
two linked single-currency entries, so the partitioner emits two legs:

```
JPY book:  Alice       owes  fx_clearing   50,000      (the debt currency)
INR book:  fx_clearing owes  Boris         27,952      (the settlement currency)
```

`fx_clearing` participates in each partition as an ordinary participant, which is what makes the
transfers come out right and what makes per-currency zero-sum (N1) meaningful rather than incidental.
It is then *classified* apart from member transfers in the plan, because its balance is the group's
external FX requirement rather than a member's position (RULE S4, RULE S5).

**The partitioner sorts the legs before handing them over, and it has to.** The engine is deterministic
only for *identical* input: the pairwise tier emits one transfer per obligation in the order they were
recorded, which `GreedyNettingTest` asserts as intended. Obligations arrive from a database in
unspecified order, so `libs/planning` normalises the order per partition. Without that, the same cycle
could yield two plans equal in every amount and unequal as values, and a preview the group approved
would not literally be the plan that executed. Sorting in the caller rather than the engine is what
keeps ADR 0012's "the engine does not change" true.

Two consequences worth stating plainly, because both are easy to get wrong:

1. **`sum(positions) == 0` is per currency.** There is no meaningful global zero, because adding
   yen to rupees is not addition.
2. **Cross-currency obligations add a transfer leg.** They are represented as two linked
   single-currency entries (ADR 0012), one of which is an `fx_clearing` leg. Under a per-transfer tax
   a cross-currency cycle is therefore **more expensive** than the same obligations settled within one
   currency. The preview must be able to report that cost, not only the saving.

A headline `taxSaved` spanning currencies is only meaningful in a **reporting currency**, using a
**reporting rate** distinct from the locked settlement rate (ADR 0013), and must be labelled as an
estimate. See `docs/specs/fx.md` sections 7 and 8.
