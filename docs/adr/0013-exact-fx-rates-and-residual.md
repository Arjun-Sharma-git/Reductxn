# ADR 0013 - Exact FX rates, cross-currency entries, and the rounding residual

- **Status:** accepted
- **Date:** 2026-09-25
- **Related:** principle P1, P12; ADR 0004, ADR 0012; RULEs L1, L6, L7, L8, L9, B18, B19

## Context

Cross-currency settlement requires multiplying an amount by a rate. That operation has exactly the
same failure mode as money arithmetic, and P1 exists because of it: a binary floating-point rate
multiplied by a `long` amount loses precision silently, and the error compounds across every
conversion in every cycle.

Three specific difficulties have to be answered, not waved at.

1. **Rates are not money.** `Money` is a `long` of minor units plus a currency. A rate like
   `JPY -> INR = 0.55904` has no currency and needs its own exact representation.
2. **Conversion must round somewhere.** `50,000 JPY` at `0.55904` is `27,952.0 INR` exactly, but
   almost every real pair is not exact. A rounding decision is unavoidable, and whatever is discarded
   is real value that has to end up somewhere.
3. **Currency exponents differ.** JPY has exponent 0, INR/USD/RUB have exponent 2. The result of a
   conversion must be rounded to the *target* currency's exponent, which is not the same granularity
   as the source.

## Decision

**1. A rate is a scaled integer, never a floating-point value.**

```java
public record FxRate(
        Currency base,
        Currency quote,
        long rateScaled,      // rate = rateScaled / 10^rateScale
        int rateScale,        // provider-native scale, typically 5-8
        long midRateScaled,   // provider mid, for disclosing the spread
        int spreadBps,        // why the applied rate differs from mid
        Instant quotedAt,
        Instant expiresAt,
        String source) {}
```

`rateScaled` and `rateScale` together represent the rate exactly. No `double`, no `float`, and - for
the same reason `Money` avoids it - no `BigDecimal` in the domain model or on the wire. This is the
same technique `Money.percentageBps` already uses: keep the arithmetic integral and make the scale
explicit rather than implicit.

**2. Conversion is multiply-exact, then divide with an explicit rounding rule.**

```
convertedMinor = roundHalfUp(amountMinor * rateScaled / 10^rateScale, targetExponent)
```

- Multiplication uses `Math.multiplyExact`; overflow throws rather than wrapping, matching `Money`.
- Division rounds half-up, deliberately matching `Money.percentageBps`, so the codebase has exactly
  one rounding rule and not two.
- The result is expressed in the **target** currency's minor units at the target's exponent.

**3. What rounding discards is booked, never dropped.**

Any remainder goes to the `fx_clearing` account for the affected currency. Value is conserved and
visible: the sum of booked residuals equals the sum of the exact differences, to the minor unit. A
residual that is silently absorbed is a real loss to an unidentified party, which is the failure
this rule exists to prevent.

**4. A conversion that rounds to zero is rejected, not performed.** If a non-zero amount converts to
zero minor units in the target currency, the obligation is refused at recording time with a clear
error. Silently producing zero would violate N3 (no non-positive transfer) and would make a real
debt vanish. JPY at exponent 0 against a high-magnitude pair makes this reachable, so it is a
first-class case rather than a theoretical one.

**5. Settlement rate and reporting rate are different things and are never interchanged.**

| | Settlement rate | Reporting rate |
|---|---|---|
| Purpose | Computes a real transfer | Values many currencies for one headline number |
| Stored | On the obligation, immutable | Derived at read time |
| Restatable | **No** | **Yes** |
| Chosen at | Obligation recording | Report generation |

The settlement rate is contractual: it is what the creditor was promised. Restating it would
misstate history. The reporting rate is presentational and is restated whenever a report is
regenerated. A single "taxSaved" figure spanning `JPY`, `RUB` and `INR` can only exist in a reporting
currency, and must be labelled as such.

**6. A cross-currency obligation fails closed when no usable rate exists.** If the provider is
unreachable, or the quote is older than the configured staleness bound, recording a cross-currency
obligation **refuses** rather than inventing a rate. Same-currency obligations are unaffected and
keep working. An unavailable rate is an availability incident; a guessed rate is an accounting
incident, and they are not equally bad.

**7. The spread is disclosed and totalled.** Storing `midRateScaled` alongside the applied rate means
the FX cost can be computed and shown, as a number analogous to "tax saved". If the product claims to
reduce cost, it must not hide a cost it introduces.

**8. Rails that perform FX are declared as such.** `PaymentRail` gains an FX capability flag. When a
rail converts, the conversion is still recorded for the books, but the entry is marked
## Consequences

**What becomes easier.**

- Rates are exactly reproducible: given a stored quote and an amount, an auditor recomputes the same
  minor units, which is the property P4 exists to protect.
- One rounding rule in the codebase, shared with tax, rather than one per module.
- The FX cost becomes a first-class reported number instead of an invisible deduction.

**What becomes harder, and must be accepted.**

- **Overflow becomes reachable.** `amountMinor * rateScaled` multiplies two large values. Amounts and
  rates must be bounded and the bounds enforced at the edge, or the engine throws on legitimate
  traffic. This needs explicit documented limits, not a comment.
- **Scale selection is a real decision.** Too few digits and the residual becomes material; too many
  and overflow arrives sooner. The provider's native scale is used and recorded, so the choice is
  visible in the data rather than buried in code.
- **Exactness costs readability.** `rateScaled`/`rateScale` is less obvious than `0.55904`, so every
  display surface must format it through `libs/fx` rather than ad hoc, and logs should carry both.
- **Rejecting zero-rounding conversions is a user-facing failure.** Users will occasionally be told
  an amount is too small to convert. That is correct behaviour, but it needs a clear localized
  message, not a generic validation error (ADR 0014).

**Alternatives considered, and why rejected.**

- **`double` or `float` for rates.** Violates P1 directly. Rejected.
- **`BigDecimal` for intermediate conversion.** Defensible in isolation, since it is exact for
  decimal fractions. Rejected because it reintroduces the scale ambiguity that P1 removes, makes
  division behaviour depend on an implicit rounding mode, is slower on the settlement path, and is
  excluded from the wire by P1. The scaled-integer form gives the same exactness with an explicit,
  recorded scale.
- **Store the rate as a string and parse it at each point of use.** Equivalent to the scaled integer,
  but with worse validation and a parse step on every use. Rejected.
- **Use the mid-market rate and describe the spread as zero.** Hides a real cost from the group.
  Rejected as dishonest, independently of the engineering.
- **Prove correctness by round-tripping `A -> B -> A` and asserting equality.** A useful additional
  property (kept as X5), but not a substitute for booking the residual, because a lossless round trip
  is not generally achievable.
- **Let each module round as it sees fit.** Guarantees two rounding rules in one release. Rejected:
  one rule, shared with `Money.percentageBps`.


