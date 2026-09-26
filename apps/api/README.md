# apps/api

HTTP boundary, identity, groups, obligations and settlement orchestration. One deployable, many internal
modules (ADR 0009), with the boundaries enforced by ArchUnit rather than by deployment distance - which is
only credible because the domain logic lives in `libs/*` and cannot be reached around.

**Public surface:** `io.reductxn.api.web.v1.*` (HTTP only, via generated DTOs).

**May depend on:** all `libs/*`, all `platform/*`.

**Must NOT depend on:** `workers/settlement` (RULE B6).

## The layers

`web` -> `application` -> `port` -> `adapter` (RULE B12-B16). Each arrow is a rule in
`ModuleBoundaryTest`, verified by sabotage rather than by a green tick.

| Package | Holds | Rule |
|---|---|---|
| `web/v1` | Inbound adapters. Three controllers: the contract's netting preview, record-an-obligation, and the run a member reads and answers (ADR 0021, 0022) | B12: never an adapter, and never `port/out` |
| `web/dto` | The wire shapes, and the one mapper between them and a plan | |
| `web/error` | RFC 9457 problem+json, and the resolver that gives a domain failure its code | B20 |
| `application` | Use cases. The only place `@Transactional` may appear | B13, B15 |
| `port/in` | What `web` may call | |
| `port/out` | What `application` may call | |
| `adapter` | Implementations of `port/out` | B14: never `application` or `web` |
| `bootstrap` | Spring wiring only, no logic | |

## Two things to know before running it

**It needs a database and a jurisdiction, and will not start without either.** There are no development
doubles left: `JooqOpenObligations` and `JooqTaxRuleCatalog` are the only implementations of their ports, so
a missing database or an unset `reductxn.tax.jurisdiction` stops the context. That replaced a
profile-gated design that existed for one reason - keeping an in-memory stub out of a deployment - and the
mechanism went with the stubs: there is nothing left to keep out. `docker compose -f
infra/docker/compose.yaml up postgres` and then
`./gradlew :apps:api:bootRun --args='--reductxn.tax.jurisdiction=IN'` is the local path.

**No business rules live here.** Which currencies a cycle spans, how each partition nets, what the tax is
and where the FX requirement lands are answered by `libs/*`. A rule that appears in `application` is a
rule in the wrong module.

## What the persistence layer looks like from here

`adapter/persistence` holds seven jOOQ adapters and no generated row types: `Record` is mapped to a domain
type inside the fetch, so nothing jOOQ-shaped leaves the package. RULE B16 enforces that, and it is
repository-wide rather than module-wide, because `libs/*` may not use jOOQ at all - which is why the rate
provider is here rather than in a package of its own. The `obligations` schema
is `apps/api`'s own - `V101`, in the `1xx` version range, with `V102` adding the `memo` and
`idempotency_key` columns the contract's request and replay need and `V103` the `quote_id` that makes a
cross-currency obligation storable at all - while `journal_entries` and `postings`
belong to `libs/ledger` (`V301`), `currencies`, `fx_rates` and `fx_quotes` to `libs/fx` (`V401`, `V402`) and
`tax_rules` to `libs/netting` (`V201`), read through read models. `platform/persistence` runs every migration
and owns none.

**The boundary crosses both ways now.** The preview reads `workers/settlement`'s tables as a projection, and
`POST /v1/runs/{runId}/decisions` *writes* into one of them: a member's answer goes into `settlement_decisions`,
because the member's surface is here and the run there, the two deployables never depend on each other (RULE B6)
and there is no broker in this build. What makes that safe is not care but design - quorum is a pure function over
the run's own rows (ADR 0016), so an answer from a member the run does not concern counts for nothing whichever
deployable wrote it. ADR 0020 records the decision, the alternatives and what it defers.

The crossing is pinned by two tests rather than by a shared type, since no type can be shared across RULE B6:
`DecisionApiTest` here, and `RecordedDecisionsTest` in the worker, which writes the same row with the same SQL
and proves the run's arithmetic judges it. Those migrations are not on this module's classpath, so this module's
*tests* take them by filesystem path; a deployment needs no such arrangement, because whichever deployable starts
first applies every migration to the one schema.

**The read side is a projection too, and it is drawn on the same line.** `GET /v1/runs/{runId}` and
`GET /v1/runs/{runId}/transfers` read the worker's run, partition, transfer and decision rows and report them: the
figures per currency, who pays whom (with the FX legs labelled from `libs/planning`'s own model of the clearing
account), each movement's state, and every answer on the record. What they do *not* do is recompute what those rows
mean - quorum, the group's external requirement and the reporting-currency totals are the worker's judgements, and a
second implementation here would be the copy a member reads while the first one moves money (ADR 0021). Fields with
no source are left unpopulated with a stated reason (`reportingTotals`, `fxRequirements`, `executedAt`), one
requirement (`TransferInstruction.tax`) was relaxed, and the tests assert the absences so that filling one is a
decision rather than an accident.

**The one judgement the run carries is the worker's own, projected.** `GET /v1/runs/{runId}` reports `gates`: whether
quorum is met with the approvals counted and required and how many debtors still have to confirm, and what each
currency is still short of - read from `settlement_run_gates`, which the worker writes when it reads its gates, and
reported without checking it (ADR 0023). A currency that is covered has no row there, so `outstanding` is what the
group has left to do; `gates.status` is the run's status when the verdict was reached, so a client can tell a current
conclusion from one that outlived its run; and a run nobody has looked at yet has no `gates` at all, because
"nobody has concluded anything" and "the gates are satisfied" are different facts.

Together the three endpoints are one story: read the plan, answer it, check the answers. A member who has answered
can now be told "you are one approval short, and it is one of the members who owes money" - the counts come from the
worker, and this module only says what it was told. What it still cannot say is *which* member: the run's
participants are the worker's positions and are not published here.

## What is not here yet

Identity, groups, and the rest of the settlement surface. An obligation's `group_id` has no foreign key because
`groups` does not exist yet, which is stated in the migration rather than left to be noticed. A cross-currency
obligation *can* be stored since `V103` and `libs/fx`'s quote tables - and it is why rate ingestion exists (ADR
0027), because a conversion with no rate is refused rather than guessed.

The sentence this paragraph used to carry - that a cross-currency obligation could not be stored because
`obligations` had no quote columns - outlived the columns by several slices. Recorded here rather than quietly
deleted: a README that says what is missing is only useful while somebody compares it against the schema, and this
one was not compared for a while.

`POST /v1/runs/{runId}/decisions` records a member's answer, approval or refusal. A refusal ends the run, and the
end is the worker's to write: this records the row, and `workers/settlement` writes `rejected` on its next look at
the run (ADR 0022 says why the outcome lands there rather than here or in the driver). A test asserts that no status
is written here, so the boundary is a failing test rather than a comment. Nothing here computes a run either -
`POST /cycles/{cycleId}/runs` and `/runs/{runId}/execute` are the operator's triggers, and the worker's driver
performs both.

The write path exists: `POST /v1/obligations` records a commitment and books the entry it produces, in one
transaction - one entry for an ordinary commitment, a linked pair through `fx_clearing` for one whose creditor
is paid in another currency. It has no route for anything else: no void, no list, no settlement.

`settleInCurrency` works, and it has two failure modes the contract distinguishes because a client does
different things about them: `fx.rate_stale` (`422`, the pair was quoted and the rate had expired) and
`fx.rate_unavailable` (`503`, nothing was quoted for the pair at or before the obligation's instant). Nothing
is written on either path. Rates come from `libs/fx`'s `fx_rates` table, and since ADR 0027 something writes them:
`ScheduledRateIngestion` reads a provider's published document on a cadence and stores the rates this deployment
can transact. A deployment with no `reductxn.rates.feed` configured ingests nothing, so every cross-currency
request is a `503` - the designed fail-closed behaviour rather than a stub.

## Rate ingestion (ADR 0027)

`fx_rates` is what every conversion reads, and `adapter/feed` is what fills it. The two are deliberately separate
from the request path: a conversion that waited for a network call to a market-data provider would make every
obligation's latency and availability the vendor's.

| Piece | What it is |
|---|---|
| `adapter/feed/EcbReferenceRates` | The ECB's daily document as `PublishedRates` - a pure parser, no I/O. Digits are parsed by hand (P1 forbids a binary floating-point type for a rate), the whole table is normalised to its own widest rate because a ratio is only exact at one scale, and the publication instant is the day the document names at 16:00 Brussels time, not midnight: a rate is not usable before it existed |
| `adapter/feed/EcbReferenceRateFeed` | `FxRateFeed` over a location that may be an `https` URL or a file path. Timeouts, because it runs in a scheduled slot; a blank document is empty, and a missing file, an unreachable host or a non-2xx answer are faults |
| `port/in/IngestRates` + `application/rates` | One pass: ask the feed, intersect with the catalogue, derive in `libs/fx`, store, report. The feed is an *argument* rather than a dependency, so a deployment with no provider starts normally |
| `port/out/RateStore` + `JooqRateStore` | The writer. `ON CONFLICT DO NOTHING` against `V403`'s unique key, returning how many rows were new |
| `adapter/schedule/ScheduledRateIngestion` | The cadence, `@ConditionalOnProperty(name = "reductxn.rates.feed")` - it does not exist without a provider. Faults are logged and swallowed: `@Scheduled` surfaces a fault by ending the schedule |

Which pairs are stored is the catalogue's answer: the currencies in `currencies`, intersected with what the feed
quotes, both directions each. Adding a currency is an insert, and the next refresh prices its pairs. `libs/fx`'s
`CrossRates` does the derivation - the ECB quotes everything against the euro, so a rupee-per-yen rate is a ratio
of two observations, derived for every pair that is kept.

## Three things about the write path worth knowing before changing it

**The transaction is load-bearing.** `RecordObligationService` is `@Transactional` because `JooqChainHeads`
takes a Postgres advisory lock per `(group, currency)` before reading the chain's head, and an advisory lock
taken for the length of a transaction protects nothing outside one. The adapter refuses to be asked outside a
transaction rather than silently returning an unlocked head, because the failure it prevents - two entries
claiming the same predecessor - is invisible in both entries.

**Codes are namespaced `api`,** not `obligation`. `DomainErrorCodeTest` derives a code's namespace from its
package's module segment, and `io.reductxn.api..` is `api`. The class comment on `ObligationRefusals` explains
the disagreement between that rule and the contract's examples in full, and why the mechanical rule won.

**A refused request must write nothing.** Every refusal is decided before the obligation row is inserted, and the
row is inserted before the entry is appended, so a duplicate idempotency key aborts the transaction before
anything is booked. The tests assert the absence, not only the status code.

**Tests:** `./gradlew :apps:api:test` (Testcontainers, real Postgres)

**Spec:** `contracts/openapi/reductxn-api.yaml` - edited first, always (RULE C1) - and
`docs/MASTER-PLAN.md` section 5.4. **Owner:** backend reviewer.
