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
| `FxRateProvider` | Port for a rate *reader*: the most recent rate for a pair as of an instant. The adapter lives in `apps/api`'s `adapter/persistence` package, because RULE B16 keeps every jOOQ type in one package and this one reads a table |
| `PublishedRates` | One provider's publication: what it quoted, against one base currency, at one instant, all at one scale. The shape a market-data provider actually publishes |
| `FxRateFeed` | Port for a rate *source*: what the provider published most recently, asked by a job rather than by a request. The adapter lives in `apps/api`'s `adapter/feed` package, which reads no table |
| `CrossRates` | The rates a deployment can transact, derived from a publication made against one base - the one division of a rate by a rate |
| `FxException` + subtypes | Failures carrying stable, non-localized codes (RULE B20) |

`io.reductxn.fx.internal` is private and must not be referenced from outside this module.

## Allowed dependencies

`libs/kernel` and `libs/ledger` only (RULE B18). Notably **not** `libs/netting`: netting nets one
currency at a time and its proofs depend on that, so it must not acquire FX awareness.

## What this module does NOT do

- It does **not** decide which currencies exist, or how old a rate may be. The catalogue is `currencies` (a table a
  deployment inserts into), and freshness is `reductxn.rates.max-age`, applied when a rate is stored.
- It does **not** fetch rates. Two adapters sit behind two ports, both in `apps/api`: `FxRateProvider` is answered
  from `fx_rates` by a class in `adapter/persistence`, and `FxRateFeed` is answered from the provider's document by a
  class in `adapter/feed` (ADR 0027). Neither package is a choice - RULE B16 pins jOOQ to one package, and a
  document-fetching adapter is not a table read.
- It does **not** apply a spread. A rate is stored as the provider's own mid rate with the spread at zero, and the
  columns for a margin exist so that adding one is not a migration. Taking a margin is a decision about what a
  creditor is promised, and it is not this module's to make.
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
