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

**It will not start without a store, and that is deliberate.** The only `OpenObligations` and
`TaxRuleCatalog` implementations are development doubles behind the `dev` profile, while the use case is
not profiled - so the default profile has no bean to inject and the context refuses to build.
`MissingStoreFailsClosedTest` asserts that refusal. Run locally with
`--spring.profiles.active=dev`; a deployment cannot reach a double by forgetting a flag.

**No business rules live here.** Which currencies a cycle spans, how each partition nets, what the tax is
and where the FX requirement lands are answered by `libs/*`. A rule that appears in `application` is a
rule in the wrong module.

## What is not here yet

Persistence, identity, and four of the five controllers the plan names. The preview therefore answers for
the obligations in the running process rather than for stored ones - which is what a development double
can know, and why the doubles are named after what they are instead of sitting in `adapter/persistence`
looking like the real thing.

**Tests:** `./gradlew :apps:api:test`

**Spec:** `contracts/openapi/reductxn-api.yaml` - edited first, always (RULE C1) - and
`docs/MASTER-PLAN.md` section 5.4. **Owner:** backend reviewer.
