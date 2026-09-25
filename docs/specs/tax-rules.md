# Spec - Tax rules

**Owner:** ledger reviewer · **Module:** `libs/netting/internal/tax` · **Related:** ADR 0006

## 1. Shape

```
tax_rules(id, jurisdiction, currency, kind[flat|advalorem|threshold],
          params_json, effective_from, effective_to, source_ref)
tax_assessments(transfer_id, rule_id, base_minor, currency, tax_minor, base_currency,
                reporting_rate_id, withheld_by)
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

The cycle end date is evaluated in the **group's settlement timezone** (ADR 0014, point 6), not in a
member's local zone and not in UTC. Otherwise two members could resolve different rules for the same
instant, and the run would not be reproducible.

In a multicurrency cycle the rule is resolved **per currency partition**, because a rule is keyed by
currency. There is no conversion of tax between currencies: tax is assessed in the currency of the
transfer, and any cross-currency total is a reporting-currency figure (section 6).

## 5. Properties (jqwik)

| # | Property |
|---|---|
| T1 | Monotonic: larger base never yields less tax (for advalorem/threshold) |
| T2 | Non-negative: tax is never negative |
| T3 | Zero base implies zero tax |
| T4 | `minMinor`/`maxMinor` bounds are respected |
| T5 | Reassessing a stored assessment yields the same number |
| T6 | A rule keyed to a currency is never applied to a transfer in another currency |

## 6. Tax currency and reporting currency

Tax is assessed **in the currency of the transfer**. A transfer in JPY is taxed by the JPY rule for
its jurisdiction; a transfer in INR by the INR rule. Nothing is converted to assess tax, because the
rail charges in the currency it moves.

This has a direct consequence for the product's headline number. A group with obligations in JPY, RUB
and INR has tax in three currencies, and those **cannot be added together**: minor units of different
currencies are not commensurable. So:

- Per-currency tax, tax saved and transfer counts are the authoritative figures.
- Any single headline "tax saved" is a **reporting-currency** figure, computed with a **reporting
  rate** that is presentational and restatable (ADR 0013, point 5).
- A reporting-currency total must be labelled as an estimate and must not be used to compute a
  transfer, an approval, or a tax assessment.

Conflating the two is how a report ends up disagreeing with the ledger: the ledger moved `27,952`
INR, and a restated report claims `28,100` was moved. The ledger figure is history; the reported
figure is an opinion about history, and they are different things.

## 7. Open question - the important one

**Are we a withholding agent?** If tax is withheld at source, reductxn may be legally obliged to
withhold and remit, which changes the licensing posture entirely. Default posture is
**compute and report**, with the rail remitting. This is an open legal item, not a settled
engineering decision, and it is tracked as a Phase 0 blocker before any real-rail integration.
