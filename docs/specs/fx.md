# Spec - FX conversion and cross-currency settlement

**Owner:** ledger reviewer · **Module:** `libs/fx` (+ `libs/ledger` for the linked entries) ·
**Review bar:** two reviewers · **Related:** ADR 0012, ADR 0013

## 1. Model

```
fx_rates(id, base_currency, quote_currency, rate_scaled, rate_scale,
         mid_rate_scaled, spread_bps, source, quoted_at, expires_at)

fx_quotes(id, base_currency, quote_currency, rate_id, amount_minor, converted_minor,
          residual_minor, quoted_at, expires_at, locked_at)

currencies(code, minor_unit_exponent, is_cbdc, jurisdiction)

journal_entries(..., fx_link_id, fx_role[base|quote], rail_performed_fx)
```

A **rate** is market data and is superseded over time. A **quote** is the locked application of a
rate to one amount, and is immutable once written. An obligation stores the quote id, never the rate
itself, so re-deriving history is not required to read a past obligation.

## 2. Rate representation

`rate = rateScaled / 10^rateScale`, exactly. `libs/fx` is the only module permitted to multiply an
amount by a rate (**RULE B19**). No `double`, no `float`, no `BigDecimal` (P1, ADR 0013).

Documented bounds, enforced at the API edge so the engine never throws on legitimate traffic:

| Quantity | Bound | Why |
|---|---|---|
| `amountMinor` | 10^15 | Keeps `amountMinor * rateScaled` inside `long` for scale 8 |
| `rateScale` | 0..8 | Provider-native scale; 8 is the practical maximum |
| `rateScaled` | 1..10^12 | A rate outside this range is a data error, not a market |

## 3. Conversion

```
convertedMinor = roundHalfUp( amountMinor * rateScaled * 10^(targetExp - sourceExp) / 10^rateScale )
```

Three details that are easy to get wrong, and are therefore stated explicitly:

1. **The exponent difference is part of the formula.** Converting between a 0-exponent currency and a
   2-exponent one moves the decimal point; forgetting it is a factor-of-100 error that looks like a
   plausible number.
2. **Rounding is half-up**, matching `Money.percentageBps`. One rounding rule in the codebase.
3. **Multiplication uses `Math.multiplyExact`**, so overflow throws rather than wrapping.

### 3.1 Worked examples, computed by hand

**JPY -> INR.** Alice (Tokyo) owes Boris (Mumbai) `50,000 JPY`. Rate `JPY->INR = 0.55904` at scale 5.

```
rateScaled = 55,904   rateScale = 5   sourceExp = 0 (JPY)   targetExp = 2 (INR)

convertedMinor = 50,000 * 55,904 * 10^(2-0) / 10^5
               = 2,795,200,000 * 100 / 100,000
               = 2,795,200
```

`2,795,200` minor units = **`INR 27,952.00`**. Cross-check: `50,000 * 0.55904 = 27,952` major units.
Correct.

**RUB -> USD.** Alice (Moscow) owes Chen (New York) `RUB 100,000`. Rate `RUB->USD = 0.010241` at
scale 6.

```
convertedMinor = 10,000,000 * 10,241 * 10^(2-2) / 10^6
               = 102,410,000,000 / 1,000,000
               = 102,410
```

`102,410` minor units = **`USD 1,024.10`**. Cross-check: `100,000 * 0.010241 = 1,024.10`. Correct.

**INR -> JPY, the reverse direction.** `INR 27,952` at `INR->JPY = 1.78878` at scale 5.

```
convertedMinor = 2,795,200 * 178,878 * 10^(0-2) / 10^5
               = 499,999,785,600 / 100,000 / 100
               = 49,999.97856
roundHalfUp -> 50,000
```

## 4. The rounding residual

Almost no real conversion is exact. Whatever rounding discards is **booked to `fx_clearing`, never
dropped** (ADR 0013, point 3).

**Worked example.** `50,001 JPY` at `JPY->INR = 0.55904`, scale 5, sourceExp 0, targetExp 2:

```
exact    = 50,001 * 55,904 * 100 / 100,000 = 2,795,255.904 minor units
booked   = roundHalfUp(2,795,255.904)       = 2,795,256     minor units
residual = exact - booked                   = -0.096        minor units
```

The group credited `0.096` of an INR minor unit more than the exact value, so `fx_clearing` is
debited `0.096` in INR. Value is conserved and the amount is visible in the books.

## 5. Cross-currency journal entries

A cross-currency obligation produces **two single-currency entries**, linked by `fx_link_id`, with
`fx_role` naming which side is which:

```
fx_link_id = L1
  JPY entry (fx_role=base):   Alice       -50,000      fx_clearing  +50,000
  INR entry (fx_role=quote):  fx_clearing -27,952      Boris        +27,952
```

Both entries are internally balanced, so **L1 and L6 hold unchanged**. There is no mixed-currency
entry anywhere in the ledger, which is what keeps the per-currency DB constraint expressible.

The linkage is a new invariant (**L9**), because a valid hash chain in one currency does not prove
that its partner entry exists in the other. Chain verification gets a second pass over `fx_link_id`.

## 6. The FX clearing account and the external FX requirement

`fx_clearing` is a real account with `owner_type = 'fx'`. It is:

- **excluded** from member netting, because it is not a member;
- **included** in the group balance report, because omitting it would make the report not balance;
- **required to be zero**, per currency, at the end of a settlement cycle.

If it is non-zero, the group has a genuine **external FX requirement**, and the run is flagged
`externally_funded` with the exact currency amounts. reductxn cannot produce currency it does not
hold (P2), so this is surfaced as a quantified obligation, in the same spirit as the default-risk
surface in `docs/specs/settlement.md` section 6.

**This is a product surface, not a footnote.** A group with a single cross-currency obligation and
nothing else *cannot* settle internally: someone must bring yen and rupees together from outside.

## 7. Interaction with tax and netting

- Netting runs **per currency**, unchanged. N1-N9 hold per currency.
- Tax is assessed in the currency of the **transfer**, using the rule effective for
  `(jurisdiction, currency)`. No change to the tax engine.
- A cross-currency obligation costs **at least one extra transfer leg** (the clearing leg), so under a
  per-transfer tax it is strictly more expensive than settling within one currency. The preview must
  be able to report the cost of the currency choice, not only the saving.
- A headline `taxSaved` across currencies must be summed in a **reporting currency** using a
  **reporting rate**, and labelled as an estimate. It must never be summed in bare minor units.

## 8. Properties (jqwik, must hold for all generated inputs)

| # | Property |
|---|---|
| X1 | Determinism: a stored quote plus an amount always yields the same minor units |
| X2 | Conservation: `exact - booked == residual`, and the cycle sum of residuals equals the `fx_clearing` delta |
| X3 | Non-vanishing: a non-zero source amount never converts to zero; it is rejected instead |
| X4 | Round-trip bound: `A -> B -> A` is within 1 minor unit of `A` for a mid-rate pair at sufficient scale |
| X5 | Monotonicity: a larger source amount never converts to a smaller result |
| X6 | Bounds: amounts and rates at the documented limits convert without throwing; beyond them it throws rather than wrapping |
| X7 | Per-currency zero-sum: after any cross-currency cycle, member positions plus `fx_clearing` sum to zero in every currency |

## 9. Rail FX capability

`PaymentRail` declares whether it performs FX. When it does, the requirement in section 6 is
satisfied by the rail and the entries are marked `rail_performed_fx = true`, with the rate the rail
used recorded for reconciliation. When it does not, the group must fund the requirement.

## 10. Open questions

- Rate provider selection, and the failover provider when the primary is stale.
- Whether the residual should ever be allocated pro rata to members, rather than accumulating.
- Staleness bound: 60 seconds is the working default for locking a quote; the correct value depends on
  provider latency and the abuse surface.
- Whether to support a rate locked at settlement time as an alternative strategy (ADR 0012, open
  question).

