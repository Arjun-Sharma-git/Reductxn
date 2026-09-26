# ADR 0027 - A rate arrives on a schedule, from a provider the deployment names

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`EcbReferenceRateFeed`, `EcbReferenceRates`, `RateStore`, `JooqRateStore`,
  `IngestRates`, `RateIngestion`, `IngestRatesService`, `ScheduledRateIngestion`, `CurrencyCatalog.all`,
  `@EnableScheduling`), `libs/fx` (`V403`, `PublishedRates`, `FxRateFeed`, `CrossRates`)
- **Answers:** `docs/specs/fx.md` section 11's first open question - "rate provider selection, and the failover
  provider when the primary is stale" - for the first half, and records the second as still open
- **Closes:** the hole every earlier slice documented and none could fix: `fx_rates` and `fx_quotes` existed, and
  nothing wrote a rate, so every cross-currency obligation was refused with `fx.rate_unavailable`
- **Related:** ADR 0003, 0012, 0013, 0018; RULE B16, B17, B19, C1; `docs/specs/fx.md` sections 1, 2, 10, 11;
  MASTER-PLAN sections 10.8, 13

## Context

`V401` and `V402` created the currency catalogue, the rates and the quotes. `JooqFxRateProvider` reads the most
recent rate at or before an instant and judges it - a fresh one is returned, an expired one is `fx.rate_stale`
(422), an absent one is `fx.rate_unavailable` (503) - and `RecordObligationService` locks a quote through it. Every
piece of the conversion chain was implemented, tested and exact. The chain had no first link: nothing wrote
`fx_rates`, so in a deployment every cross-currency obligation was refused, correctly and uselessly.

The spec named it as an operational path rather than a gap in the module ("a rate loader is an operational path - a
provider feed, or an operator's insert - and it is not part of this module, which is why the table exists and is
empty"), and MASTER-PLAN's own ordering put it second, after the scheduler: "then rate ingestion, which is what a
deployment actually needs before a group can record a cross-currency commitment at all". Two other statements were
waiting on it. `FxRateProvider`'s class comment said the adapter lives in `adapter/persistence` "because RULE B16
keeps every jOOQ type inside that one package - so a rate source that reads a table cannot be anywhere else", and
then made a promise: "a provider that fetched rates over the network instead of from the database could live
anywhere in the module, **and the day one exists this sentence should say so**". And the recipe for adding a rate
provider (MASTER-PLAN section 10.8) named a package that no longer exists (`apps/api/adapter/rate/`), because RULE
B16 had already made it impossible, and a WireMock contract test - a dependency this repository does not have.

## Decision

**1. A provider is chosen, named and recorded on every rate it produces.** The European Central Bank's daily
reference rates are the first feed: no key, no contract, published every working day at about 16:00 Brussels time
as a small XML document, and quoted by every currency the project's worked examples use except the rouble - which
the bank suspended in 2022, and which the tests assert as an absence rather than paper over. The identifier `ecb`
is stored in `fx_rates.source`, which is what makes a rate defensible to an auditor rather than only executable.

**2. Ingestion is a job, not a request path.** `ScheduledRateIngestion` on a cadence
(`reductxn.rates.refresh`, six hours), reading `reductxn.rates.feed`, which may be the provider's URL or a path an
ops process has already fetched the document to. A conversion that waited for a network call would make every
obligation's latency and availability the vendor's; a deployment with no feed configured ingests nothing and keeps
refusing cross-currency work loudly, which is `@ConditionalOnProperty` on the job rather than a flag inside it.
This is the second `@Scheduled` component in the repository and the first in `apps/api`, which is why
`@EnableScheduling` arrives with it.

**3. A publication is observations against one base, and the pairs are derived.** The bank publishes "units of X per
one euro", not pairs - so `PublishedRates` models exactly that (a base code, one instant, one scale, and units per
base per currency), and `CrossRates` derives every ordered pair among the currencies the deployment can transact.
The derivation is one multiply and one divide: the scale cancels in a ratio, so a pair is `q / b` as integers, with
half-up rounding at eight digits. It lives in `libs/fx` because rate arithmetic is that module's by RULE B19 and
because a second implementation is how two rates for one pair appear. The adapter parses digits by hand: P1 forbids
a binary floating-point type for a rate, and this is a rate arriving from outside the process.

**4. Which pairs are worth storing is the catalogue's answer, not a configuration list.** The set ingested is the
currency catalogue intersected with what the feed quotes. Every pair a deployment can oblige in is priced; nearly
nine hundred rates a day of pairs nobody can oblige in are not; and adding a currency stays what `V401` says it is -
an insert, not a release - with the next refresh pricing its pairs.

**5. A rate expires on the deployment's terms, and the default is four days.** `reductxn.rates.max-age` is how old a
published rate may be when a client locks a quote, applied at ingestion as `expires_at = quoted_at + max-age`. Four
days because this provider publishes on working days only: Friday's rate is the most recent one until Monday
afternoon, and any bound shorter than a weekend would refuse every cross-currency obligation for it while the
provider behaves perfectly. The spec's working default of sixty seconds describes a streaming provider, which is why
the number is a deployment property rather than a constant beside the rate.

**6. Storing a publication twice is a no-op, and the database says so.** `V403` makes `(base_code, quote_code,
source, quoted_at)` unique and `JooqRateStore` inserts with `ON CONFLICT DO NOTHING`, returning how many rows the
database accepted. A scheduled job has no memory of its last run, a second instance may overlap it and an operator
may repeat it; giving the job that memory would be a second record of what the table already records, and without
the constraint the second reading of one day's rates would duplicate market data that every reader then resolves by
recency. `source` is in the key, so a failover provider's rate for the same pair and instant is a second fact rather
than a conflict.

**7. No spread is applied, and the columns that would hold one are already there.** The rate stored is the
provider's own mid rate with `spread_bps` zero, built with `FxRate.ofMid` - a name that says no spread was applied
rather than leaving a reader to infer it from two equal fields. Applying a margin is a decision about what a
creditor is promised, which is a group's and a deployment's, not a loader's; storing the mid rate now means adding
one later is not a migration.

**8. Absent and broken are different, and neither is silent.** A blank document is `Optional.empty` - the provider
has published nothing readable yet - and the job says so at info and writes nothing. A missing file, an unreachable
host and a non-2xx answer all throw, and the job reports them at warn and tries again: a refresh that turned a
provider outage into "nothing to do" would leave a deployment with no rates and a calm-looking log.

## Consequences

- **Cross-currency obligations work in a deployment for the first time.** That is the point of the change, and the
  test asserts both halves: the same commitment is refused 503 before the feed is read and created after it, through
  the HTTP API, with the rate, the converted amount and the residual the conversion implies.
- **A derived rate says so in its source.** `ecb` for a pair quoted against the publication's own base, `ecb-cross`
  for one the provider never published: the bank published no rupee-per-yen rate, so an auditor who finds one under
  its name and goes looking in its archive must not come away thinking the number is missing.
- **A publication that cannot be derived in full is refused in full.** Both directions of every pair are composed
  before anything is written, so a currency the feed does not quote or a rate outside the bounds throws with nothing
  stored - rather than storing one direction, or half a day, and leaving the rest to fail closed at an unpredictable
  moment. The cost is that one absurd rate costs a whole refresh, which is a data incident either way.
- **Two sets of codes are reported, and they mean different things.** Codes the feed quotes that the catalogue does
  not hold are information (the bank quotes thirty; a deployment knows three). Codes the catalogue holds that the
  feed quotes nowhere are a warning, because every obligation in that currency will be refused until somebody adds a
  provider or drops the currency - which is why `CurrencyCatalog` gained `all()`, a method that exists to name an
  absence rather than to iterate a set.
- **The catalogue grows now, not at build time.** A deployment that adds EUR to `currencies` gets euro pairs at the
  next refresh, including the ones whose base is the euro, which carry the provider's own name. No release, and no
  list to keep in step with the catalogue.
- **One provider, and a stale primary is still an open question.** The spec's section 11 asks for a failover
  provider as well; this ADR answers the selection half and leaves the failover half exactly where it was, because a
  second provider is a second trusting relationship and a policy for choosing between them, not a second URL.
- **A stated bound is still only partly enforced, and this ADR records it rather than pretending otherwise.**
  `docs/specs/fx.md` section 2 says `rateScaled` is 1..10^12. `CrossRates` enforces it on the rates reductxn
  *invents*, and neither `FxRate`'s constructor nor a CHECK constraint enforces it on a rate that arrives any other
  way. That was already true; deriving rates made it matter, and it is now a finding in MASTER-PLAN rather than a
  sentence in a spec nobody compares against a database.
- **The port's own promise is kept.** `FxRateProvider`'s comment said a provider that fetched rates over the network
  could live anywhere in the module and that the sentence should be corrected the day one existed. It exists, and
  the comment now says where the two adapters are: the reading one in `adapter/persistence` because it reads a
  table, the fetching one in `adapter/feed` because it does not.
- **A file path is a feature, not a test seam.** The feed accepts a path as well as a URL, which is how a deployment
  with no outbound access runs and what makes the ingestion testable end to end with no network - the bank's own
  document, written to a temporary file, read by the same adapter a deployment uses.

## Alternatives considered - and why rejected

**An operator's insert: a command line or an admin endpoint.** The spec names it as an option ("a provider feed, or
an operator's insert"), it needs no vendor and no network, and an endpoint would be the wrong kind of surface: this
module has no identity yet, so an unauthenticated write of market data is an unauthenticated way to move money -
every conversion would take its rate from whoever could reach the endpoint. A command line would work, and it makes
the deployment's rate freshness a human's memory. The loader is a job with a provider behind it, and both halves are
tested.

**Fetch on demand, inside the provider.** `JooqFxRateProvider` could fetch the document when the table has nothing
usable. It removes the schedule, and it puts a vendor's latency and availability on the request path of every
conversion, makes a request's *answer* depend on a fetch that happened during it, and turns a data-loading problem
into a settlement outage. Rejected for the same reason ADR 0018 keeps triggers off the worker's own decisions.

**Derive the crosses in the adapter.** The adapter knows the observation table and the pair it needs, so the division
could sit there and `libs/fx` would not grow. It would also mean two places that multiply or divide rates, and
RULE B19 exists precisely because that goes wrong quietly - a rate is exact arithmetic with bounds, not a
conversion applied to a number.

**Store only what the provider published, and invert at read time.** Half the rows, no derived source to explain -
and a reader that inverts a rate is a reader doing rate arithmetic, plus a second rounding that no row records. Both
directions are derived and stored, so reading a rate is a lookup and the rounding is in the row.

**Ingest every pair the feed can express.** Thirty quoted currencies is 870 ordered pairs a day, none of which a
deployment can oblige in unless the currency is in the catalogue - and the catalogue is where "which currencies can
we transact" is already answered. A configured list was the other version of the same mistake: a second statement
of the catalogue, which would go stale silently.

**A long staleness bound as a default, rather than a per-deployment one.** Sixty seconds is what the spec's own
example uses, and applying it to a daily reference feed would refuse every conversion for a rate the provider is
publishing exactly as often as it promises. Making the number configuration rather than a constant is what lets a
streaming provider set seconds and this one set days.

**A WireMock contract test, as MASTER-PLAN section 10.8 prescribes.** This repository has no WireMock dependency,
and the recipe's own package (`apps/api/adapter/rate/`) was made impossible by RULE B16 before the recipe was
written. What replaces it is stronger for this provider: the bank's *real* document is a test fixture, parsed and
then ingested from a file, so the contract being tested is the one that ships - and the HTTP branch is three lines
of `java.net.http` with a connect and an answer timeout around the same parser.

**Apply a spread at ingestion.** The columns are there (`rate_scaled` and `mid_rate_scaled`) and a margin could be
configured and stored, making every conversion slightly better for the deployment. It would also change what a
creditor is promised on the basis of a loader's configuration, before anyone has decided that this product takes a
margin, how large it is or who it is disclosed to. The mid rate is stored, and the disclosure columns stay honest.

**A CHECK constraint for the spec's rate bound at the same time.** `rateScaled` is stated as 1..10^12 and nothing in
the database enforces it. Adding it is a second, backwards-looking change (existing rows, the type's behaviour on
reads, and `JooqFxRateProvider` which rebuilds rates from rows) and it belongs with the decision to enforce it in
`FxRate` too. Recorded as a finding instead of half-done here.
