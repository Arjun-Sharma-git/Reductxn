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
is written here, so the boundary is a failing test rather than a comment. Nothing here computes a run either: a
cycle is opened (`POST /v1/groups/{groupId}/cycles`), and the plan over it is the worker's cadence, which computes
every cycle that has no run - in the minute after the cycle is opened (ADR 0032, 0033). The contract used to offer
`POST /cycles/{cycleId}/runs` for that, and it is gone: a client that had a cycle id would have nothing left to ask
for, and no client could obtain one, because the operation that opens a cycle answered with no body.

`POST /v1/groups/{groupId}/cycles` is the second write into the worker's rows, and the smaller one. It writes four
columns of `settlement_cycles` and mints its own id, because the pair `(group_id, as_of)` is what identifies a
cycle and the database settles which id survives - the worker's `JooqRuns.open` reads back whichever row the pair
has and writes the run against it. It refuses nothing, and the absence is the decision: a group's commitments are
this module's rows, so "nothing to settle" is answerable here, and answering it here would refuse a request the
worker satisfies a minute later, because a commitment recorded tomorrow can carry an instant inside this cycle.

`GET /groups/{groupId}/members` and `POST /groups/{groupId}/members` are this module's own data rather than a
crossing (ADR 0035): `V104` creates `group_members`, the read serves the group in the order people joined, and the
write records one - idempotent on the pair `(group_id, user_id)`, so a second request answers `200` with the row that
exists. **The read had been promised by the contract for several slices with nothing behind it**, and the client
called it from three screens all along; the stub-generated browser tests could not see it and this module had never
asked for members, which is what made it a gap worth an ADR rather than a missing file. `Member.creditLimit` and the
`admin only` claim on the write were removed rather than left unimplemented: nothing sets or consults a limit, and a
build without identity has no admin whose word would authorise anything.

`GET /v1/obligations` is the read that closes the largest of ADR 0035's four gaps, and the one the app's main screen
has always called (ADR 0036): a page of a group's commitments, narrowed by status, member, dates and a two-word
sort, with a clamped `limit` in place of the `cursor` the contract used to promise and never served. **The status is
derived rather than stored** - `voided_at` for one word, the settlement worker's runs for two more, all four decided
in one expression (`ObligationStatuses`) that the response's column and the `status=` filter both read, so a page
cannot contain a row whose status it would not display. A run that *ended without settling* releases its
commitments: a commitment nothing is settling is owed, which is the same reading the preview uses. The replay path
(`JooqObligationStore.findExisting`) derives the status the same way, so a retry cannot call a settled commitment
open.

`GET /v1/currencies` is the second of those reads and the quietest, in the way that matters: the contract says a
client needs the exponent to render a `Money` at all, so its absence did not make one screen show a uuid, it stopped
money rendering anywhere. It serves a code and an exponent, ordered by code, and deliberately not the two columns
(`is_cbdc`, `jurisdiction`) that no consumer reads yet.

`GET /v1/fx/rates` is the third, and it is where the module's only *business* decision about a read lives (ADR 0037):
it answers the rate a pair converts at **and applies the same freshness policy as locking**, so a pair whose newest
rate has expired is refused with `fx.rate_stale` (422, final) rather than answered with a stale number — because the
recording path would refuse that same submission, and showing the number would invite a member to fill in the form
and be refused later. A pair nothing has quoted is `503 fx.rate_unavailable`, which is retryable and a different
answer; both codes are the provider's own, so the read and the write fail identically. The response is the contract's
whole `FxRate` schema — `quotedAt` and `expiresAt` included, so a screen never presents an old number as current — and
`FxRateResponse` is now the one type that describes a rate anywhere, shared with a locked quote (ADR 0037).

`GET /v1/groups/{groupId}/balances` is one of ADR 0038's recorded gaps now served, and the only one this module could
close without a decision belonging elsewhere (ADR 0039). It is **the netting preview's own computation**: the same
open obligations through the same port, the same planner, the same positions - flattened into one entry per member per
currency, with the partitions and transfers nobody asked for left out of the response rather than out of the work.
That is what stops two screens disagreeing about what somebody owes, which is ADR 0036's argument applied to a second
pair of endpoints. It has no `asOf` (a balance is "now"; a past instant is the preview's own question), it includes a
`flat` member rather than dropping the row, and it **needs a tax rule exactly as the plan does**, because the engine
assesses tax on the transfers it emits - so a group holding a currency with no rule in force is told that here rather
than shown a number nobody could reproduce. One consequence is recorded rather than hidden: a cross-currency group has
a position whose holder is the `fx_clearing` account, and the `NetPosition` schema carries no way to say so yet, so a
client shows the identifier and the screen's footnote names both cases.

`GET /v1/me` and the three auth operations are sign-in, and they are the first thing in this build that reads a token
(ADR 0042). A phone number and a code: verifying a code **is** registering - a number nobody has seen becomes an account,
because in an OTP build there is no separate sign-up and no admin to seed one. Three details are decisions rather than
details. **A code is stored as a PBKDF2 hash with a per-challenge salt while a session token is a plain SHA-256** - twenty
bits need a cost per guess and 256 random bits do not. **The code is never returned and never logged by this module**; it
goes through `OneTimeCodes`, whose only adapter in this deployment writes it to the log at `WARN`, which is the stand-in
named rather than implied. And **`bearerFormat: JWT` is gone**: the token is opaque, hashed at rest, and rotation rewrites
the row - both tokens, in one conditional update, without moving the session's end, so a replayed refresh token finds
nothing and the old access token dies with it.

The one thing this slice deliberately did not do is require a token anywhere except `/me`. Enforcing it on the members
write, the invitation acceptance and the decision endpoint is a slice of its own: it would break every existing caller at
once, and doing it here would hide all of that behind identity's arrival.

`POST /v1/groups/{groupId}/invites` and `POST /v1/invites/{code}/accept` are the last two operations of section 6's
route tree, and serving them closes the route tree's screens (ADR 0041). The slice also created the table, and the shape
of the data followed from the wire: acceptance is addressed by the code alone, so **the code is the primary key** and is
unique across the deployment rather than within a group - an `id` column would be a second identifier that nothing
addresses. Creation has no request body in the contract, so the window (seven days) and the limit (none) are this
deployment's decision, stated in the operation's description so a reader knows what they are getting.

The rule the slice is about is what accepting a code does to somebody the group already has: **becoming a member is
idempotent, so it is a success that changed nothing, and nothing is spent** - an invitation is spent by joining somebody,
and nobody joined. That is why the acceptance asks about the membership *before* spending a use, and the two tests that
pin it down are the slice's sharpest: an already-a-member acceptance leaves the row's `uses` at one, and a one-use code
is still available for the person the group actually invited. Expired and exhausted are two codes under the one `410` the
contract has declared since the path was added, and a code nobody holds is `404`: a mistyped code is a code for nothing,
not a code that ran out.

`GET /v1/tax/rules` and `GET /v1/tax/reports` are the last two operations of MASTER-PLAN section 6's route tree besides
the group's invites, and they are the only ones in the contract that had a path, a summary, parameters and **no response
schema at all** (ADR 0040). The report is therefore a shape this slice chose: one line per currency, `grossTax` recovered
as `netTax + taxSaved` (the same arithmetic the preview's mapper does for one partition), and `runCount` so a reader can
tell one cycle's tax from a quarter's. **It is a report about plans, not about money that moved** - no run can reach
`done` in this build, and what a group owes is what its plans assess - so it reads the stored plan rather than re-running
the engine, over the cycle's `as_of` with the end day included in full, and counts every computed plan except the two
states that mean it did not happen: `rejected` and `failed` release what they held (ADR 0036), and their tax with it. It
has **no headline figure**, because a total across currencies needs a reporting currency the operation deliberately does
not offer (ADR 0012), and because `docs/specs/tax-rules.md` section 7 leaves the withholding question open with a
posture of compute-and-report - a figure here is never evidence that tax was remitted.

The rules read answers what is in force **now**, which is the same call the reference rate read makes (ADR 0037):
a run stores the figures its own assessment produced, so what a cycle was taxed under is a fact about the run rather than
something a reader reconstructs from the rules table. It resolves the window with the **same expression the settlement
path uses** (`TaxRuleWindow`), because two copies of a condition agree everywhere except where it matters - and the test
suite asserts end to end that the rule a group is shown is the rule its preview charges.

`GET /v1/groups/{groupId}/cycles` is the read that makes it visible (ADR 0034). It exists for the reason
`GET /v1/groups/{groupId}/runs` does, one act earlier: opening a cycle writes a claim about an instant, the plan over
it is the worker's business a minute later, and without the list a member who asked and a member whose cycle the
worker refused to plan would see the same thing - nothing. Each row carries `runId`, a fact rather than a status:
present means the plan is readable at `/runs/{runId}`, absent means there is none, and the two situations behind
"none" are deliberately not told apart, because telling them apart would mean recording a refusal this module does
not judge. The read is one query - a left join to the worker's `settlement_runs`, which `one_run_per_cycle` keeps
row-preserving - ordered by `created_at` through the index `V511` creates for it.

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
