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
| `web/v1` | Inbound adapters. One controller today, for the contract's netting preview | B12: never an adapter, and never `port/out` |
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

`adapter/persistence` holds two jOOQ repositories and no generated row types: `Record` is mapped to a domain
type inside the fetch, so nothing jOOQ-shaped leaves the package. RULE B16 enforces that, and it is
repository-wide rather than module-wide, because `libs/*` may not use jOOQ at all. The `obligations` schema
is `apps/api`'s own - `src/main/resources/db/migration/V101__obligations.sql`, in the `1xx` version range -
while `tax_rules` belongs to `libs/netting` and is read through a read model. `platform/persistence` runs
both and owns neither.

## What is not here yet

Identity, groups, and four of the five controllers the plan names. An obligation's `group_id` has no foreign
key because `groups` does not exist yet, and cross-currency obligations cannot be stored because the
`obligations` table has no quote columns - both are stated in the migration rather than left to be noticed.

**Tests:** `./gradlew :apps:api:test` (Testcontainers, real Postgres)

**Spec:** `contracts/openapi/reductxn-api.yaml` - edited first, always (RULE C1) - and
`docs/MASTER-PLAN.md` section 5.4. **Owner:** backend reviewer.
