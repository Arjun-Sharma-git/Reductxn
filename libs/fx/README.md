# libs/fx

Exact currency conversion. Pure Java: no Spring, no JDBC, no HTTP (RULE B5, P4).

## Purpose

Turn an amount in one currency into an amount in another, **exactly**, and account for the part that
rounding discards. This is the only module in reductxn permitted to multiply an amount by a rate
(RULE B19, ADR 0013).

## Public surface

Everything callers may touch lives in `io.reductxn.fx.api` (RULE B10).

| Type | Role |
|---|---|
| `FxRate` | A rate as `(rateScaled, rateScale)`. Never a floating-point value (P12) |
| `FxConversion` | Converted amount plus the fractional rounding residual |
| `FxQuote` | An immutable, locked conversion of one amount; `FxQuote.lock(...)` validates freshness |
| `FxConverter` / `FxConverters` | The conversion operation, reached through `FxConverters.exact()` |
| `FxRateProvider` | Port for a rate source. The adapter lives in `apps/api`'s `adapter/persistence` package, because RULE B16 keeps every jOOQ type in one package |
| `FxException` + subtypes | Failures carrying stable, non-localized codes (RULE B20) |

`io.reductxn.fx.internal` is private and must not be referenced from outside this module.

## Allowed dependencies

`libs/kernel` and `libs/ledger` only (RULE B18). Notably **not** `libs/netting`: netting nets one
currency at a time and its proofs depend on that, so it must not acquire FX awareness.

## What this module does NOT do

- It does **not** fetch rates. That is an adapter behind `FxRateProvider`, and it lives in `apps/api` - the
  module that also stores obligations, so that a rate is read and a quote is written by the same deployable.
  Its package is `adapter/persistence` rather than a package of its own, because RULE B16 keeps every jOOQ type
  inside that one package and a provider that reads a table cannot be anywhere else.
- It does **not** format a rate or an amount for display. That is the client, per locale
  (RULE F11, ADR 0014). `FxRate.pair()` is for logs, not for users.
- It does **not** post to the ledger, and it does not know what an `fx_clearing` account is. It
  computes the residual and hands it over; the ledger owns what happens to it.
- It does **not** decide how to compose a cross-currency settlement. That is the per-currency
  partitioner, which lands in phase 2.5 (ADR 0012).

## The two things worth knowing before changing anything here

1. **The trailing-zero cancellation is load-bearing.** It looks like a tidy-up and is not: it is what
   keeps `amountMinor * rateScaled` inside a `long` for realistic pairs. Removing it turns legitimate
   conversions into overflow exceptions. See `docs/specs/fx.md` section 3.2.
2. **The residual is fractional** and stays fractional. It is carried as
   `residualScaled / 10^residualScale`, not as a whole minor unit. Rounding it to an integer would
   lose or invent value on every conversion.

## Tests

`./gradlew :libs:fx:test`

Unit tests cover the worked examples from `docs/specs/fx.md`, the residual, the zero-rounding refusal,
and the overflow boundary. The jqwik property tests assert X1-X6 from that spec; X2 checks
conservation against an independent `BigInteger` oracle rather than against the implementation.
