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
| `web/v1` | Inbound adapters. Two controllers: the contract's netting preview, and record-an-obligation | B12: never an adapter, and never `port/out` |
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

`adapter/persistence` holds five jOOQ adapters and no generated row types: `Record` is mapped to a domain
type inside the fetch, so nothing jOOQ-shaped leaves the package. RULE B16 enforces that, and it is
repository-wide rather than module-wide, because `libs/*` may not use jOOQ at all. The `obligations` schema
is `apps/api`'s own - `V101`, in the `1xx` version range, with `V102` adding the `memo` and
`idempotency_key` columns the contract's request and replay need - while `journal_entries` and `postings`
belong to `libs/ledger` (`V301`), `currencies` to `libs/fx` (`V401`) and `tax_rules` to `libs/netting`
(`V201`), read through read models. `platform/persistence` runs every migration and owns none.

## What is not here yet

Identity, groups, and three of the five controllers the plan names. An obligation's `group_id` has no foreign
key because `groups` does not exist yet, and a cross-currency obligation cannot be stored because the
`obligations` table has no quote columns - both are stated in the migrations rather than left to be noticed.

The write path exists now: `POST /v1/obligations` records a commitment and books the entry it produces, in one
transaction. It has no route for anything else - no void, no list, no settlement - and one of its inputs does
not work: `settleInCurrency` is answered `503` with `fx.rate_unavailable`, because `UnconfiguredRateProvider` is
the only implementation of `libs/fx`'s port and it has no rate source. That is the fail-closed behaviour the
contract describes rather than a stub, and the class comment says what the slice that adds a provider has to
bring with it.

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
