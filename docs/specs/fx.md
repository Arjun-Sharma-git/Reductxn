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

Documented bounds. The third row is the one that actually binds, and the earlier draft of this spec
got it wrong by stating the first three as if they were independent.

| Quantity | Bound | Why |
|---|---|---|
| `rateScale` | 0..8 | Provider-native scale; 8 is the practical maximum |
| `rateScaled` | 1..10^12 | A rate outside this range is a data error, not a market |
| `amountMinor` | 0..10^15 | Generous for a group settlement |
| **the product** | `amountMinor * rateScaled * 10^max(0, expShift) < 2^63` | **This is the real limit.** The conversion is a single 64-bit multiply, so three individually reasonable bounds can still multiply past `long` |

The engine uses `Math.multiplyExact`, so a violation **throws rather than wraps**. That is the
required behaviour, not a fallback.

**Trailing zeros in the rate are cancelled before multiplying**, which is exact and materially widens
the usable range, because provider rates at scale 8 usually carry them:

```
INR -> JPY  rateScaled 178,878,000 at scale 8
            -> cancels to 178,878 at scale 5     (3 factors of ten)
INR -> JPY  rateScaled    17,888 at scale 4
            -> cannot cancel: 17,888 is not a multiple of 10
```

So the practical ceiling depends on the pair: roughly `9.2 * 10^6` minor units for the worst-case
rate that survives cancellation, against about `5.1 * 10^13` minor units for `INR -> JPY`
(about INR 500 billion). Adequate for this product, and worth knowing before someone records a
sovereign-scale obligation and gets an exception instead of a conversion.

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

### 3.2 The exact algorithm

Stated as steps, because the order matters and two of them are what keep it inside a `long`:

```
1. reject a negative amount        direction belongs to the transfer, not the amount (L2)
2. reject amount not in rate.base
3. cancel trailing zeros           while exponent > 0 and factor % 10 == 0: factor /= 10; exponent--
4. num = multiplyExact(amountMinor, factor)
5. expShift = targetExp - sourceExp
       expShift > 0:  num = multiplyExact(num, 10^expShift)
       expShift < 0:  exponent += -expShift
6. cancel trailing zeros from num  while exponent > 0 and num % 10 == 0: num /= 10; exponent--
7. denominator = 10^exponent
   quotient    = num / denominator
   remainder   = num % denominator
   roundUp     = (2 * remainder >= denominator)          half-up, same rule as Money.percentageBps
8. convertedMinor = quotient + (roundUp ? 1 : 0)
   residualScaled = roundUp ? remainder - denominator : remainder
   residualScale  = exponent
9. reject if convertedMinor == 0 while the amount was non-zero    (X3, never silently vanish)
```

Steps 3 and 6 are cancellation, not approximation: dividing both the numerator and the denominator
by ten retains the exact same rational value. They exist purely to postpone overflow.

Step 7 is safe against overflow because `exponent` is bounded at 16 (`rateScale` 8 plus an exponent
shift of at most 8), so `2 * remainder < 2 * 10^16`, comfortably inside a `long`.

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

### 4.1 The residual is fractional, and is represented as one

`-0.096` is not a whole minor unit, so a single `int64` cannot hold it. The residual is therefore
represented the same way a rate is (P12), as a scaled integer:

```
residual = residualScaled / 10^residualScale        residualScaled = -96, residualScale = 3
```

The invariant, which is exactly checkable in integers:

```
(convertedMinor * 10^residualScale + residualScaled) * D  ==  N  * 10^residualScale

where N = amountMinor * rateScaled * 10^max(0, expShift)
      D = 10^rateScale * 10^max(0, -expShift)
```

Property X2 asserts this identity against an independent `BigInteger` oracle, so the test recomputes
the exact value rather than checking the implementation against itself.

`residualScaled` is always in `[-0.5, 0.5)` minor units, which is asserted separately as a range
check: a residual outside that band would mean the rounding rule and the recorded residual disagree.

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
| X2 | Conservation: `(convertedMinor * 10^residualScale + residualScaled) * D == N * 10^residualScale`, checked against an independent `BigInteger` oracle; plus `residualScaled` within `[-0.5, 0.5)` minor units |
| X3 | Non-vanishing: a non-zero source amount never converts to zero; it is rejected instead |
| X4 | Round-trip bound: for an exactly invertible rate and same-exponent currencies, `abs(A -> B -> A - A) <= ceil(0.5 + 0.5/r)`, which is **1 minor unit for every `r >= 1`** |
| X5 | Monotonicity: a larger source amount never converts to a smaller result |
| X6 | Bounds: amounts and rates at the documented limits convert without throwing; beyond them it throws rather than wrapping |
| X7 | Per-currency zero-sum: after any cross-currency cycle, member positions plus `fx_clearing` sum to zero in every currency |

### 8.1 Notes on X2 and X4

**X2** uses `BigInteger` as an *oracle in the test*, which is not a P1 violation: P1 forbids floating
point and `BigDecimal` in the domain model and on the wire. A test that recomputes the exact rational
value independently is the only way to check the implementation against something other than itself.
Its second half, "the cycle sum of residuals equals the `fx_clearing` delta", needs the ledger, so it
is asserted in the phase that wires conversion into postings, not in `libs/fx`.

**X4's bound is derived, not chosen.** Rounding the forward conversion costs at most `0.5` target
minor units, which is at most `0.5/r` source minor units; rounding the return conversion costs at most
`0.5` source minor units. Total `<= 0.5 + 0.5/r`. So the "within 1 minor unit" formulation is exactly
right for `r >= 1` and **wrong for `r < 1`**, where the bound grows: at `r = 0.1` it is `5.5`.
The property test therefore generates rates with `r >= 1` and same-exponent currency pairs, and relies
on an *exactly* invertible rate so the comparison is not measuring the inverse rate's own rounding.

**X7 is deferred.** It is a property of the ledger and the netting partitioner, not of `libs/fx`. It
is listed here so the requirement is not lost, and it lands in the phase that wires conversion into
postings (`docs/MASTER-PLAN.md` section 13, phase 2.5).

## 9. Rail FX capability

`PaymentRail` declares whether it performs FX. When it does, the requirement in section 6 is
satisfied by the rail and the entries are marked `rail_performed_fx = true`, with the rate the rail
used recorded for reconciliation. When it does not, the group must fund the requirement.

## 10. Implementation status

**Implemented in `libs/fx`** (34 tests green): the scaled-integer rate with validating construction,
the exact conversion with both cancellation points, the fractional residual, and `FxQuote.lock` with
its fail-closed freshness check. Properties X1-X6 hold over generated inputs.

**Not implemented, and each for a stated reason:**

| Missing | Why it is not here |
|---|---|
| X7, per-currency zero-sum | Tested in `libs/planning`, which composes the partitions. It needs a module that can see both the engine and `fx_clearing` |
| X2's second half, "the cycle sum of residuals equals the `fx_clearing` delta" | Needs postings to sum over, so it lands with the ledger write path |
| The `fx_clearing` account and its postings | Needs the ledger write path in `apps/api` |
| `awaiting_fx_funding` and the funding state | Needs the settlement run lifecycle in `workers/settlement` |
| A rate provider adapter | Needs `apps/api/adapter/rate`; the port (`FxRateProvider`) is ready |

**Measured against the compiled engine.** Every one of these reproduces the hand-computed value in the
sections above, which is the check that the spec and the code agree rather than merely coexisting:

```
JPY  50,000  ->  2,795,200 INR minor units       INR 27,952.00      residual 0
JPY  50,001  ->  2,795,256 INR minor units       residual -0.096
JPY  50,006  ->  2,795,535 INR minor units       residual +0.424    (rounds down)
RUB 100,000  ->    102,410 USD minor units       USD 1,024.10       residual 0
INR  27,952  ->     50,000 JPY minor units       (round trip, within one minor unit)
INR       1  ->  refused: fx.amount_too_small_to_convert
```

## 11. Open questions

- Rate provider selection, and the failover provider when the primary is stale.
- Whether the residual should ever be allocated pro rata to members, rather than accumulating.
- Staleness bound: 60 seconds is the working default for locking a quote; the correct value depends on
  provider latency and the abuse surface.
- Whether to support a rate locked at settlement time as an alternative strategy (ADR 0012, open
  question).

