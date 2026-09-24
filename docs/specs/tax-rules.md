# Spec - Tax rules

**Owner:** ledger reviewer · **Module:** `libs/netting/internal/tax` · **Related:** ADR 0006

## 1. Shape

```
tax_rules(id, jurisdiction, currency, kind[flat|advalorem|threshold],
          params_json, effective_from, effective_to, source_ref)
tax_assessments(transfer_id, rule_id, base_minor, tax_minor, withheld_by)
```

A rule is **data**. Adding a jurisdiction is an insert, never a release.

## 2. The three shapes

**flat** - fixed charge per transfer. `params: { perTransferMinor }`.
Objective: **minimise transfer count**.

**advalorem** - percentage of amount. `params: { rateBps, minMinor?, maxMinor? }`.
Objective: **minimise transferred volume**. Netting already helps; the optimizer can help further.

**threshold** - tiered or progressive. `params: { tiers: [{ uptoMinor, rateBps }], fixedMinor? }`.
Objective: rule-dependent. Modelled generally; the optimizer receives a pluggable objective.

## 3. Engine contract

```java
public interface TaxRule {
    Money assess(Money base, Currency currency);
    Objective objective();          // MIN_TRANSFERS | MIN_VOLUME | CUSTOM
}
```

The optimizer's objective is **derived from the active rule**, which is why tax and netting live in
the same module.

## 4. Effective dating

Rules are effective-dated. A settlement run resolves exactly one rule per
`(jurisdiction, currency)` for the cycle end date. Historical runs that are re-executed use the
rule effective at their original date, so recomputation is byte-identical.

## 5. Properties (jqwik)

| # | Property |
|---|---|
| T1 | Monotonic: larger base never yields less tax (for advalorem/threshold) |
| T2 | Non-negative: tax is never negative |
| T3 | Zero base implies zero tax |
| T4 | `minMinor`/`maxMinor` bounds are respected |
| T5 | Reassessing a stored assessment yields the same number |

## 6. Open question - the important one

**Are we a withholding agent?** If tax is withheld at source, reductxn may be legally obliged to
withhold and remit, which changes the licensing posture entirely. Default posture is
**compute and report**, with the rail remitting. This is an open legal item, not a settled
engineering decision, and it is tracked as a Phase 0 blocker before any real-rail integration.
