# reductxn - Master Plan & Architecture

**Status:** Draft v0.2 · **Last updated:** 2026-09-25
**Audience:** engineers, reviewers, security & compliance, external auditors
**Companion docs:** `docs/adr/*`, `docs/specs/{netting,tax-rules,ledger,settlement,fx,i18n}.md`, `docs/THREAT-MODEL.md`

---

## 0. How to read this document

This is the single source of truth for **how reductxn is put together**: the product shape,
the module graph, and - most importantly - **the mechanical rules that keep the code modular**
as the team and the codebase grow.

| Marker | Meaning | If you break it |
|---|---|---|
| **RULE** | Mechanically enforced (ArchUnit / ESLint / CI gate) | The build fails |
| **GUIDE** | Strong convention, reviewed by humans | A reviewer pushes back |
| **NOTE** | Context, rationale, trade-off | Nothing |

Changing a **RULE** requires an ADR (section 12). **GUIDE**s evolve in code review.

---

## 1. What reductxn is

A **non-custodial group commitment ledger with tax-optimised CBDC settlement**.

Members of a trusted group record intended transfers ("I owe Priya 4,000") as **obligations**.
No money moves. At cycle end, reductxn computes every member's **net position** and reduces the
group's O(N^2) pairwise obligations to at most N-1 **real CBDC transfers** - the minimum needed
to settle everyone. Because CBDC rails levy a **transaction tax per transfer**, netting collapses
the group's tax bill at the same time.

The two headline numbers in the product are:

1. **Who pays whom, and how much** - the netting report.
2. **Tax saved** - tax if settled pairwise vs. tax on netted transfers.

### 1.1 What reductxn is not

- **Not a wallet.** It never holds CBDC and never holds private keys.
- **Not a money transmitter.** It produces settlement *instructions*; a licensed rail executes them.
- **Not a bank.** It does not extend credit. Netting redistributes risk within a group, and that
  risk is surfaced in the product, not hidden.

---

## 2. Non-negotiable principles

Every design decision is measured against these invariants.

| # | Principle | Rationale |
|---|---|---|
| **P1** | Money is an **integer minor unit** (`long`) plus a currency. Never `double`, never `float`, never `BigDecimal` on the wire. | Rounding bugs in money code are silent, cumulative and unrecoverable. |
| **P2** | **Non-custodial.** No CBDC balances, no private keys, no pooled funds. | Determines licensing posture; the difference between shipping and a two-year legal slog. |
| **P3** | The **ledger is append-only and authoritative, server-side**. The client is never the source of truth. | Auditability. A mutable balance is an unauditable balance. |
| **P4** | The **domain core is pure** - no Spring, no JDBC, no HTTP inside `libs/ledger` or `libs/netting`. | An auditor must be able to run the netting engine standalone and reproduce a settlement. |
| **P5** | The **OpenAPI contract is the source of truth** for the HTTP surface. Types are generated, never hand-duplicated. | Removes FE/BE drift without a shared runtime. |
| **P6** | **Modularity is enforced mechanically**, not by convention or good intentions. | Conventions decay; failing builds do not. |
| **P7** | **One backend language.** No polyglot. | Two build systems, two pipelines, two on-call surfaces is a net loss for a small team. |
| **P8** | Every money-moving or state-changing operation is **idempotent** and **audited**. | Networks retry; users double-tap; regulators ask questions. |
| **P9** | **No pre-1.0 dependencies in the frontend.** GA releases only. | We are not a QA lab for our own dependency tree. |
| **P10** | **Tax law is configuration, not code.** A jurisdiction's rule is a `TaxRule` record, not a branch. | Tax law is the fastest-changing thing in the system. |
| **P11** | Every module has **exactly one reason to change** and **one owning table set**. | This is what makes the module graph in section 5 meaningful rather than decorative. |
| **P12** | An **FX rate is a scaled integer**, exactly like money. No floating point in valuation, ever. | A `double` rate times an exact amount is a silent error, and it compounds across every conversion in every cycle. |
| **P13** | All user-facing text is **localized**, and the API returns **stable codes, never prose**. | Adding a language must never require a backend release, and translated prose cannot be asserted in tests or diffed in logs. |
---

## 3. System context

```
                +--------------------------------------+
                |            Group members             |
                |      (browser / PWA / mobile)        |
                +------------------+-------------------+
                                   | HTTPS (JSON over OpenAPI)
                                   v
                +--------------------------------------+
                |  apps/web                            |
                |  React 19 - Vite - TanStack Router   |
                |  Query / Form / Table / Virtual      |
                |  GSAP animations                     |
                +------------------+-------------------+
                                   | /v1/...  (generated typed client)
                                   v
 +------------------+   +-------------------------------------+   +-----------------+
 |  Identity / KYC  |<--|  apps/api                           |-->| Object storage  |
 |  provider        |   |  Spring Boot 4.1 - Java 25 LTS      |   | reports/exports |
 +------------------+   |  virtual threads - MVC - jOOQ       |   +-----------------+
 +------------------+   |  netting runs per currency (0012)   |
 |  FX rate provider|<--|  exact rates live in libs/fx (0013) |
 |  mid + spread    |   +---+-----------+-------------+-------+
 +------------------+       |           |             |
             +--------------+           |             +-----------------+
             v                          v             v
 +----------------------+   +---------------------+    +-----------------------+
 | PostgreSQL 16        |   | Redis               |    | Kafka / Redpanda      |
 | jOOQ - Flyway        |   | cache, locks,       |    | transactional outbox  |
 | append-only ledger   |   | idempotency keys,   |    | -> consumers          |
 +----------------------+   | rate limits         |    +-----------+-----------+
                            +---------------------+                |
                                                                   v
                                                     +-----------------------+
                                                     | workers/settlement    |
                                                     | Spring Batch + solver |
                                                     +-----------+-----------+
                                                                 |
                                                                 v
                                                     +-----------------------+
                                                     | libs/rails            |
                                                     | PaymentRail ports     |
                                                     +-----------+-----------+
                                                                 |
                +-------------------+----------------------------+------------------+
                v                   v                                               v
       +-----------------+  +----------------+                          +----------------+
       | MockRail (dev)  |  | CBDC sandbox   |                          | Licensed PSP   |
       +-----------------+  +----------------+                          +----------------+
```

**Key boundary:** reductxn's database is **not** the ledger of record for money. Once a transfer
executes, the rail is authoritative for that movement; reductxn stores the rail reference and
reconciles against it later. That is the operational meaning of the non-custodial posture (P2).

**Two external dependencies that are new and should be treated as genuine ones.** The **FX rate
provider** is on the write path for cross-currency obligations: no rate means no cross-currency
obligation can be recorded (ADR 0013, point 6 - fail closed). A stale or unavailable provider is an
availability incident for a subset of traffic, and it needs its own runbook and staleness alerting
rather than being discovered as a spike in rejected requests.

The **locale catalogs** are the other one, and they are deliberately *not* external: they ship in the
build, so text on a money-moving screen cannot change under a user between deploys (ADR 0014).

---

## 4. Repository structure

**RULE R1** - the top-level layout below is fixed. Adding a top-level directory requires an ADR.
**GUIDE R2** - every directory contains a `README.md` stating its purpose, its public surface,
and what it is allowed to depend on.

```
reductxn/
|
+-- README.md
+-- docs/
|   +-- MASTER-PLAN.md              # THIS DOCUMENT
|   +-- adr/                        # Architecture Decision Records
|   +-- specs/
|   |   +-- netting.md              # algorithm, invariants, optimality guarantee
|   |   +-- tax-rules.md            # TaxRule shapes + per-jurisdiction configuration
|   |   +-- ledger.md               # double-entry model, hash chain, invariants
|   |   +-- settlement.md           # cycle lifecycle, quorum, rail execution
|   |   +-- fx.md                   # rates, conversion, residual, clearing account
|   |   +-- i18n.md                 # locales, ICU catalogs, locale-safe money
|   +-- THREAT-MODEL.md
|   +-- runbooks/
|
+-- contracts/
|   +-- openapi/
|   |   +-- reductxn-api.yaml       # SOURCE OF TRUTH for the HTTP surface
|   +-- events/
|       +-- *.json                  # Kafka event schemas (versioned)
|
+-- settings.gradle.kts
+-- build.gradle.kts
+-- build-logic/                    # Gradle convention plugins
+-- gradle/libs.versions.toml       # single dependency version catalogue
+-- gradlew + gradlew.bat
|
+-- libs/                           # PURE Java. No Spring, no JDBC, no HTTP.
|   +-- kernel/                     # Result, DomainError, ids, Clock, value types
|   +-- ledger/                     # Money, JournalEntry, Posting, invariants
|   +-- netting/                    # greedy + optimal netting, TaxRule engine
|   +-- fx/                         # FxRate, conversion, valuation (ADR 0013)
|   +-- rails/                      # PaymentRail port + adapters
|
+-- apps/
|   +-- api/                        # Spring Boot 4.1.1 modular monolith
|   +-- web/                        # React 19 + Vite + TanStack + GSAP
|       +-- locales/                # ICU message catalogs, per locale (ADR 0014)
|
+-- workers/
|   +-- settlement/                 # Spring Batch jobs + Kafka consumers
|
+-- platform/                       # cross-cutting, NON-DOMAIN concerns
|   +-- observability/              # OTel, Micrometer, Logback config
|   +-- persistence/                # DataSource, PgBouncer, jOOQ config, Flyway
|   +-- security/                   # Spring Security, JWT, WebAuthn
|   +-- testing/                    # Testcontainers base classes, jqwik generators
|
+-- infra/
|   +-- docker/compose.yaml         # postgres, redis, redpanda, minio
|   +-- k8s/                        # manifests / helm
|   +-- k6/                         # load test scenarios
|   +-- ci/                         # pipeline definitions
|
+-- tools/
    +-- codegen/                    # OpenAPI -> TS client / Java interfaces
```

### 4.1 Directory purity rules

| Directory | May contain | May NOT contain |
|---|---|---|
| `libs/*` | pure Java, records, algorithms, ports | Spring, JDBC, HTTP, JSON libs, logging frameworks |
| `apps/api` | controllers, security, jOOQ repositories, mappers | netting algorithms, tax computation formulas |
| `workers/settlement` | batch jobs, orchestration, progress, quorum | business rules not already living in `libs/*` |
| `platform/*` | framework configuration, infrastructure adapters | domain concepts (`Obligation`, `NetPosition`, `Money`) |
| `contracts/*` | schemas, generated types | business logic |
| `apps/web` | UI, client state, animations | money arithmetic of record |
| `apps/web/locales` | ICU message catalogs | logic, interpolation beyond ICU |

**NOTE:** the "no domain concepts in `platform/`" rule is what keeps infrastructure swappable.
If `platform/persistence` mentions `Obligation`, a boundary has already leaked.
---

## 5. Backend modularity: the Gradle module graph

Modularity is a **dependency graph**, not a folder tree. Gradle enforces it: a module only sees
another module's `api` dependencies, and a cycle is impossible by construction.

### 5.1 Module graph and dependency rules

```
                          +-----------+
                          |  kernel   |
                          +-----+-----+
                                ^
              +-----------------+-----------------+
              |                 |                 |
         +---------+       +----------+      +----------+      +----------+
         | ledger  |       | netting  |      |    fx    |      |  rails   |
         +----+----+       +-----+----+      +-----+----+      +-----+----+
              ^                  ^ ^               ^ ^               ^
              |                  | |               | |               |
              |                  | +------+--------+ |               |
              |                  |        |          |               |
              |                  +--------+----------+               |
              |                           |                           |
              |                    +------v------+                    |
              |                    | settlement  |                    |
              |                    +------+------+                    |
              |                           |                           |
              +-------------+-------------+---------------------------+
                            |
          +-----------------+------------------+
          |                                    |
  +-------v--------+                  +--------v-----------+
  |   apps/api     |                  | workers/settlement |
  +----------------+                  +--------------------+
```

**RULE B1** - `libs/kernel` depends on nothing.
**RULE B2** - `libs/ledger` depends only on `libs/kernel`.
**RULE B3** - `libs/netting` depends only on `libs/kernel` and `libs/ledger`.
**RULE B4** - `libs/rails` depends only on `libs/kernel` and `libs/ledger`.
**RULE B5** - `libs/*` never depends on `apps/*`, `workers/*`, `platform/*`, or Spring.
**RULE B6** - `apps/api` and `workers/settlement` never depend on each other; shared logic moves
*down* into `libs/*`.
**RULE B7** - `platform/*` never depends on `apps/*` or `workers/*`.
**RULE B8** - no module cycles, ever.
**RULE B18** - `libs/fx` depends only on `libs/kernel` and `libs/ledger`. It may not depend on
`libs/netting`, so rate logic can never hide inside the netting engine.
**RULE B19** - **conversion happens only in `libs/fx`.** No other module multiplies an amount by a
rate, and no other module may read `fx_rates` or `fx_quotes`. Everything else sees a converted amount
that has already been computed, or nothing at all.
**RULE B20** - the API never returns localized prose. Problem responses carry a stable `code` plus
structured `params`; localization happens in the client (P13, ADR 0014).
**RULE B21** - `libs/settlement` depends only on `libs/kernel`, `libs/ledger`, `libs/netting` and
`libs/fx`. It is the only module permitted to compose per-currency plans, and the only place
`fx_clearing` is modelled. It is pure: no I/O, no clock, no provider.

**NOTE:** RULE B6 is the one people fight. When settlement needs "the same" logic as the API, the
answer is never a `common` module containing everything - it is to extract the actual concept into
`libs/*` under a name that describes it.

**`libs/settlement` is the first working example of that rule**, so the reasoning is recorded rather
than left in a commit message. Partitioning a multicurrency cycle is needed by two modules that may
not see each other (B6): `apps/api`, to preview what a cycle would cost, and `workers/settlement`, to
build the run. Leaving it in the worker would have forced the API to reimplement it, which is two
implementations of the same financial arithmetic drifting apart. And it could not go in
`libs/netting`, which may only depend on kernel and ledger (B3) - giving the netting engine FX
awareness is exactly what ADR 0012 forbids, because its properties depend on one currency at a time.
So it moved *down* into a pure module that both deployables consume.

The naming split is deliberate and worth stating, because both modules contain the word:

| Module | Owns |
|---|---|
| `libs/settlement` | **What** must be settled: the per-currency plan, the partitions, the `fx_clearing` position, the external FX requirement. Pure and deterministic |
| `workers/settlement` | **Executing** it: the run lifecycle, quorum, idempotency, rail submission, funding state |

### 5.2 Gradle module template

```
libs/netting/
+-- build.gradle.kts
+-- README.md                                  # purpose, public surface, allowed deps
+-- src/main/java/io/reductxn/netting/**
+-- src/test/java/io/reductxn/netting/**       # unit + jqwik property tests
+-- src/test/resources/                        # golden fixtures
```

`build.gradle.kts` uses the `java-library` plugin so consumers only see `api(...)` dependencies:

```kotlin
plugins { `java-library`; id("reductxn.java-conventions") }

dependencies {
    api(project(":libs:kernel"))          // part of this module's public surface
    api(project(":libs:ledger"))
    implementation(libs.timefold)         // hidden from consumers
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
}
```

**RULE B9** - all convention plugins live in `build-logic/`; no module hand-rolls compiler settings.
Java 25, preview features off, `-Werror`, `-parameters`, `-Xlint:all`.

### 5.3 Package discipline inside a module

Each module exposes an **`api`** package and hides everything else behind **`internal`**.

```
io.reductxn.netting
+-- api/                     # PUBLIC - other modules may use these
|   +-- NettingEngine.java
|   +-- NettingRequest.java
|   +-- NettingResult.java
|   +-- NetPosition.java
|   +-- TransferInstruction.java
+-- internal/                # PRIVATE - implementation detail
    +-- GreedyNetting.java
    +-- SubsetSumOptimizer.java
    +-- tax/
        +-- TaxRule.java
        +-- FlatPerTransferTaxRule.java
        +-- AdValoremTaxRule.java
        +-- ThresholdTaxRule.java
        +-- TaxRuleEngine.java
```

**RULE B10** - no class outside `io.reductxn.<module>.api` may be referenced by another module.
**RULE B11** - `internal` types never appear in `api` signatures (no leaking `GreedyNetting` in a
`NettingEngine` return type).

These two rules are enforced by ArchUnit (section 5.6) and are the single biggest reason a
codebase stays modular years in. Without them, "modular" only means "in a different folder".

**B11 was not enforced, and said it was.** Section 5.4 made that claim for the life of the project
while only B10 existed, which is the purest example of the problem this repository keeps hitting: an
unenforced rule reads exactly like an obeyed one. It is now `b11_api_never_exposes_an_internal_type`,
covering return types, parameter types, thrown types and field types - and it is verified by breaking
it, because the first implementation was itself vacuous. Written as `noClasses().should(condition)`
over a custom condition, it passed against a deliberate sabotage; negation does not compose with custom
conditions the way it does with built-in ones. It is a positive rule now, and failing on purpose is
what established that.

### 5.4 Inside `apps/api`: hexagonal layers

`apps/api` is a **modular monolith**: one deployable, many internal modules.

```
io.reductxn.api
+-- ReductxnApplication.java
+-- bootstrap/            # Spring wiring only, no logic
+-- web/                  # ADAPTER (inbound)
|   +-- v1/
|   |   +-- AuthController.java
|   |   +-- GroupController.java
|   |   +-- ObligationController.java
|   |   +-- SettlementController.java
|   |   +-- TaxController.java
|   +-- dto/              # generated from OpenAPI + hand-written mappers
|   +-- error/            # RFC 9457 problem+json mapping
+-- application/          # USE CASES - transaction boundaries live here
|   +-- group/
|   |   +-- CreateGroupUseCase.java
|   |   +-- InviteMemberUseCase.java
|   +-- obligation/
|   |   +-- RecordObligationUseCase.java
|   |   +-- VoidObligationUseCase.java
|   +-- settlement/
|       +-- OpenCycleUseCase.java
|       +-- ComputeSettlementUseCase.java
|       +-- ApproveSettlementUseCase.java
|       +-- ExecuteSettlementUseCase.java
+-- domain/               # thin; rich logic lives in libs/*
+-- port/                 # PORTS (interfaces owned by the application)
|   +-- in/               # called by web
|   +-- out/              # implemented by adapter
+-- adapter/              # ADAPTER (outbound)
|   +-- persistence/      # jOOQ repositories, row <-> domain mappers
|   +-- rail/             # PaymentRail implementations (delegating to libs/rails)
|   +-- messaging/        # outbox publisher, Kafka
|   +-- identity/         # KYC / OTP provider clients
+-- config/               # @Configuration classes only
```

**RULE B12** - `web` may only call `application` (through `port/in`). `web` never touches `adapter`.
**RULE B13** - `application` may only call `port/out`, never concrete `adapter/*` classes.
**RULE B14** - `adapter` may not call `application` or `web`.
**RULE B15** - no `@Transactional` outside `application`.
**RULE B16** - no persistence row types (jOOQ records) cross out of `adapter/persistence`.

**NOTE:** a controller injecting a repository is the most common way this architecture dies.
RULE B12 fails the build when it happens.

### 5.5 Module ownership of data

**RULE B17** - each table has exactly **one owning module**. Cross-module reads go through that
module's `api` or through a read model - never a direct join across an ownership boundary in
application code.

| Table set | Owning module |
|---|---|
| `users`, `devices`, `sessions`, `kyc_*` | `apps/api` - identity |
| `groups`, `group_members`, `invites` | `apps/api` - group |
| `ledger_accounts`, `journal_entries`, `postings` | `libs/ledger` (schema per `docs/specs/ledger.md`) |
| `obligations` | `apps/api` - obligation |
| `settlement_cycles`, `settlement_runs`, `settlement_transfers`, `net_positions` | `workers/settlement` |
| `tax_rules`, `tax_assessments` | `libs/netting` - tax |
| `fx_rates`, `fx_quotes`, `currencies` | `libs/fx` (schema per `docs/specs/fx.md`) |
| ICU message catalogs | `apps/web` - in the build artefact, never in the database |

**NOTE:** `currencies` is owned by `libs/fx` rather than `libs/ledger` because the minor-unit exponent
is what conversion needs first, and because a currency is reference data, not a ledger concept.

**NOTE:** settlement tables are owned by the worker because that is where they are written. The
API reads them through a read model. This is deliberate: it keeps the API write path fast and lets
settlement scale out independently.

### 5.6 Enforcing it: ArchUnit + Spring Modulith

Modularity is a **test**, not a wish.

```java
@AnalyzeClasses(packages = "io.reductxn")
class ModuleBoundaryTest {

  @ArchTest static final ArchRule libs_are_pure =
      noClasses().that().resideInAPackage("..libs..")
          .should().dependOnClassesThat().resideInAPackage("org.springframework..")
          .because("RULE B5 / P4: libs/* must stay framework-free");

  @ArchTest static final ArchRule no_module_cycles =
      slices().matching("io.reductxn.(*)..").should().beFreeOfCycles()
          .because("RULE B8");

  @ArchTest static final ArchRule api_and_worker_are_independent =
      noClasses().that().resideInAPackage("io.reductxn.api..")
          .should().dependOnClassesThat().resideInAPackage("io.reductxn.settlement..")
          .because("RULE B6");

  @ArchTest static final ArchRule web_never_touches_adapters =
      noClasses().that().resideInAPackage("..api.web..")
          .should().dependOnClassesThat().resideInAPackage("..api.adapter..")
          .because("RULE B12");

  @ArchTest static final ArchRule transactional_only_in_application =
      noMethods().that().areAnnotatedWith(Transactional.class)
          .should().beDeclaredInClassesThat()
          .resideOutsideOfPackage("..api.application..")
          .because("RULE B15");

  @ArchTest static final ArchRule internal_is_private =
      noClasses().that().resideOutsideOfPackage("..netting..")
          .should().dependOnClassesThat().resideInAPackage("..netting.internal..")
          .because("RULE B10");
}
```

Plus **Spring Modulith** (`ApplicationModules.of(ReductxnApplication.class)`) to verify the
internal module graph, detect cycles, and generate living architecture documentation.

**GUIDE:** ArchUnit rules live beside the module they protect and fail with a message naming the
RULE id, so a failing build tells you which principle you broke.
---

## 6. Frontend modularity: feature-sliced, one-directional

The web app is organised by **feature slice**, and imports may only flow downward.

```
src/
+-- app/                  # composition root
|   +-- router.tsx        # createRouter, type registration
|   +-- providers.tsx     # QueryClientProvider, theme, error boundary
|   +-- guards.tsx        # beforeLoad auth guards
+-- routes/               # TanStack Router file-based routes (THIN)
|   +-- __root.tsx
|   +-- _authenticated/   # pathless layout route -> beforeLoad auth guard
|   |   +-- route.tsx
|   |   +-- dashboard.tsx
|   |   +-- groups/
|   |       +-- index.tsx
|   |       +-- $groupId/
|   |           +-- route.tsx
|   |           +-- index.tsx             # balances + activity
|   |           +-- obligations.tsx       # ?status&member&from&to&sort (Zod)
|   |           +-- netting.tsx           # netting preview + GSAP graph
|   |           +-- cycles.tsx            # timeline scrubber
|   |           +-- members.tsx           # TanStack Table
|   |           +-- settlement/
|   |               +-- index.tsx         # runs list
|   |               +-- $runId.tsx        # who-pays-whom + tax saved
|   +-- login.tsx
|   +-- invites.$code.tsx
+-- features/             # one folder per vertical capability
|   +-- auth/  groups/  obligations/  netting/  settlement/  tax/  members/
+-- entities/             # domain-shaped client models + query options
|   +-- group/  obligation/  netPosition/  user/
+-- shared/               # NO business knowledge
    +-- api/              # generated client + fetch wrapper
    +-- money/            # formatting/parsing of minor units, locale-aware
    +-- i18n/             # ICU catalog loader, locale resolution, direction token
    +-- ui/               # design system primitives
    +-- gsap/             # timelines, useGSAP hooks, matchMedia presets
    +-- lib/              # date, result, invariant helpers
    +-- config/           # env parsing (Zod-validated)
locales/                  # ICU catalogs per locale, en is the source of truth
```

### 6.1 Import direction rules

```
routes  -->  features  -->  entities  -->  shared
```

**RULE F1** - imports only flow **left to right** in the chain above. No upward imports.
**RULE F2** - a feature may not import another feature's internals. Cross-feature reuse moves
*down* into `entities/` or `shared/`.
**RULE F3** - `shared/*` may not import `features/*`, `entities/*`, or `routes/*`, and contains
**no business rules** (no netting math, no tax logic).
**RULE F4** - `routes/*` files are **thin**: parse search params, call a loader, render one feature
component. No business logic, no data transformation.
**RULE F5** - all HTTP access goes through `shared/api`. No `fetch`/`axios` calls scattered in
components. (The frontend equivalent of RULE B12, and it matters just as much.)
**RULE F6** - all server state goes through TanStack Query. No `useEffect` + `useState` data
fetching, ever.
**RULE F7** - no GSAP timelines inside `entities/` or `shared/api`. Animation lives in
`features/*` or `shared/gsap`.
**RULE F8** - a feature's public surface is `features/<name>/index.ts`. Anything not re-exported
there is private.
**RULE F9** - no arithmetic on money in the frontend except integer minor-unit arithmetic in
`shared/money`. (Backs P1.)
**RULE F10** - no user-facing string literals in components. All text comes from the ICU catalog
(ADR 0014). An intentional exception carries `// i18n-ignore: <reason>`.
**RULE F11** - all money, number and date formatting goes through `shared/money` and `shared/i18n`.
Never a raw `Intl.NumberFormat` applied to a `number` amount, because that reinstates the
floating-point path P1 forbids.
**RULE F12** - user amount input is parsed to integer minor units by string manipulation. Never
`parseFloat`; over-precise input is rejected rather than truncated.

### 6.2 Enforcing it: ESLint boundaries

```js
// eslint.config.js (excerpt)
{
  plugins: { boundaries },
  settings: {
    'boundaries/elements': [
      { type: 'app',      pattern: 'src/app/*' },
      { type: 'routes',   pattern: 'src/routes/*' },
      { type: 'features', pattern: 'src/features/*', capture: ['feature'] },
      { type: 'entities', pattern: 'src/entities/*' },
      { type: 'shared',   pattern: 'src/shared/*' },
    ],
  },
  rules: {
    'boundaries/element-types': ['error', {
      default: 'disallow',
      rules: [
        { from: 'app',      allow: ['routes','features','entities','shared'] },
        { from: 'routes',   allow: ['features','entities','shared'] },
        { from: 'features', allow: ['entities','shared'] },
        { from: 'entities', allow: ['shared'] },
        { from: 'shared',   allow: ['shared'] },
      ],
    }],
    'boundaries/no-private': ['error', { allowUncles: false }],
  },
}
```

`eslint-plugin-boundaries` plus `eslint-plugin-import-x` also ban deep imports into a feature's
internal folders, which is what RULE F2 depends on.

### 6.3 Frontend conventions

| Concern | Convention |
|---|---|
| Data fetching | `entities/<x>/queries.ts` exporting `queryOptions()` factories |
| Mutations | `features/<x>/mutations.ts`; optimistic + rollback for obligations |
| Search params | Zod schema per route, consumed via `validateSearch` + `zodValidator` |
| Money display | only via `shared/money`; components never write `amount / 100` |
| Animations | `shared/gsap/presets.ts` + `features/<x>/animations.ts`, all under `matchMedia` |
| Devtools | router/query/table devtools, dev only |
| Store | Zustand 5, one store per feature slice, **UI state only** |

---

## 7. Contract-first pipeline

```
contracts/openapi/reductxn-api.yaml
        |
        +--> openapi-typescript  --> apps/web/src/shared/api/schema.d.ts
        |                                  |
        |                            openapi-fetch --> typed client
        |
        +--> openapi-generator   --> apps/api DTO interfaces (compile-time check)
        |
        +--> Zod schemas         --> forms + search-param validation
```

**RULE C1** - the spec is edited **first**; generated artefacts are never hand-edited.
**RULE C2** - generated files carry `/* AUTO-GENERATED - DO NOT EDIT */`, are excluded from review,
but **committed** so builds are reproducible.
**RULE C3** - CI fails if regenerating produces a diff (spec and code out of sync).
**RULE C4** - wire format is `camelCase`; `snake_case` exists only inside Postgres.
---

## 8. Enforcement gates (CI)

Modularity that is not checked is not real. Every pull request runs:

| Gate | Tool | Fails when |
|---|---|---|
| Module graph | ArchUnit + Spring Modulith | RULE B1-B21 violated. **Live**: 16 rules, all verified non-vacuous |
| Rule coverage | `RuleCoverageTest` - **live** | A RULE declared in this plan *or in `docs/specs`* is neither enforced nor explicitly deferred, a coverage entry is stale, or a claimed enforcement does not resolve to something that runs |
| Property coverage | `PropertyCoverageTest` - **live** | A property declared in a spec has no assertion labelled with its id and no recorded reason, or a test labels a property that no spec defines |
| Import boundaries | `eslint-plugin-boundaries` | RULE F1-F12 violated |
| Money discipline | lint ban on `double`/`float` in domain + ESLint ban on division by 100 | P1 violated. **Live**: the `forbidFloatingPointMoney` task in `build-logic` |
| Import style | `forbidWildcardImports` task - **live** | A wildcard import appears (RULE N1) |
| FX exactness | jqwik X1-X7 - **live** | A conversion is non-deterministic, the residual is not booked, or the round trip drifts |
| FX isolation | ArchUnit - **live** | A rate is multiplied anywhere outside `libs/fx` (B18, B19). Non-vacuous, and guarded against becoming vacuous |
| Error-code convention | `DomainErrorCodeTest` - **live** | A code is malformed, namespaced by the wrong module, duplicated, or not declared as a constant |
| Locale coverage | catalog check | A key is missing versus `en`, unused, or a literal appears in a component (F10, I1-I3) |
| Error-code parity | codegen check | A server error `code` has no catalog entry, or vice versa (I3). **Not built.** Its server half now exists: every code is an enumerable constant, which is what a catalog check needs |
| Locale money safety | Vitest property tests | Formatting changes the digits, or a `float` enters the format/parse path (F11, F12, I5) |
| Spec sync | codegen diff check | RULE C3 violated |
| Ledger invariants | jqwik property tests | A journal entry does not balance, or an FX link is unpaired (L9) |
| Netting correctness | jqwik + golden fixtures | Sum of nets != 0 per currency, or a member ends negative |
| Settlement idempotency | Testcontainers replay test | Double execution changes state |
| Coverage | JaCoCo - `libs/*` **95%**, overall **75%** | Below threshold |
| Frontend | Vitest + Playwright | Any failure |
| Dependency policy | version catalogue check | Any pre-1.0 dependency in `apps/web` (P9) |
| Security | dependency + secret scan | Known critical CVE |

**GUIDE:** gates that protect RULES are non-negotiable and are not skipped for hotfixes.

---

## 9. Ownership

Ownership means: reviews changes, owns the tests, owns the runbook. It does not mean exclusivity -
it means accountability.

| Module | Owns | Primary reviewer |
|---|---|---|
| `contracts/openapi` | API surface, versioning | API reviewer |
| `libs/kernel` | value types, `Result`, ids | Platform reviewer |
| `libs/ledger` | double-entry model, invariants | **Ledger reviewer** |
| `libs/netting` | algorithms, tax engine, proofs | **Ledger reviewer** |
| `libs/rails` | rail ports + adapters, reconciliation | Integrations reviewer |
| `apps/api` | identity, groups, obligations, HTTP | Backend reviewer |
| `workers/settlement` | cycle lifecycle, batch, quorum | Backend reviewer |
| `apps/web` | UX, routes, animations, accessibility | Frontend reviewer |
| `platform/*` | infra config, observability, pools | Platform reviewer |
| `infra/*` | environments, IaC, load tests | Platform reviewer |

**NOTE:** `libs/ledger` and `libs/netting` carry a stricter review bar - two reviewers, one of whom
is the ledger reviewer - because a defect there is a financial defect.

---

## 10. Extension recipes

Modularity is proven by how cheaply the system absorbs change. These recipes must stay short.

### 10.1 Add a new payment rail
1. Implement `PaymentRail` in `libs/rails/internal/<name>/`.
2. Register the adapter in `apps/api/adapter/rail/`.
3. Add the rail to `tax_rules` (rails can differ in tax treatment).
4. Add a WireMock contract test plus a reconciliation test.
5. **No changes** to `libs/netting`, `apps/api/application`, or `apps/web`.

### 10.2 Add a new tax rule shape
1. Implement `TaxRule` in `libs/netting/internal/tax/`.
2. Add the discriminator to `tax_rules` (Flyway migration) and to `docs/specs/tax-rules.md`.
3. Add jqwik properties: monotonicity, non-negativity, zero-base implies zero-tax.
4. **No changes** to controllers, UI, or the settlement worker.

### 10.3 Add a country / jurisdiction
1. Insert `tax_rules` rows with `jurisdiction`, `currency`, `effective_from`.
2. Add the currency to the `currencies` reference table (minor-unit exponent).
3. UI: mount the existing tax report feature with the new filter. **No new code** in `features/tax`.

### 10.4 Add a new report
1. Read model in the owning module (`workers/settlement` or `apps/api`).
2. Endpoint + spec entry in `contracts/openapi`.
3. Regenerate the client; add a route rendering a TanStack Table feature.

### 10.5 Animation policy (GSAP)
**GUIDE:** animate only to communicate **state, position, or quantity change**. Forbidden:
decorative motion, animation that delays data visibility, and any animation without a
`prefers-reduced-motion` branch. Timelines are registered in `shared/gsap/presets.ts` so
reduced-motion handling is uniform.

### 10.6 Add a new currency
1. Insert into `currencies`: code, minor-unit exponent, `is_cbdc`, jurisdiction.
2. Add a tax rule for `(jurisdiction, currency)` (recipe 10.2/10.3).
3. Add the currency pair to the FX provider configuration, with a staleness bound.
4. UI: nothing. Currency labels and formatting follow from `Currency.minorUnitExponent` (P1).
5. **No changes** to `libs/ledger`, `libs/netting`, `libs/fx` code, or any controller.

Note that JPY (exponent 0) and INR (exponent 2) already coexist, so a new exponent is not new code.

### 10.7 Add a new language
1. Add `apps/web/locales/<locale>/*.json` seeded from `en`, and an entry in `_meta.json` with
   `"reviewed": false`.
2. Add the locale to the resolver's supported list, and to the Playwright locale matrix.
3. Translate. A human review flips `"reviewed"` to `true`, which is what permits the locale on
   money-moving and approval screens.
4. Check the plural categories the locale needs. Russian needs four forms; a two-form translation is
   wrong and the catalog check cannot detect that, only the reviewer can.
5. **No backend changes at all.** If a new error needs wording, that is step 3 of a backend change
   (adding a `code`), not a localization change (RULE B20).

### 10.8 Add a rate provider
1. Implement the provider port in `apps/api/adapter/rate/` (the port itself is owned by
   `libs/fx/api`).
2. Configure the staleness bound and the failover order.
3. Add a WireMock contract test, plus a test that a stale quote **refuses** a cross-currency
   obligation rather than guessing (ADR 0013, point 6).
4. **No changes** to `libs/ledger`, `libs/netting`, the settlement worker, or the UI.

---

## 11. Forbidden patterns

| Forbidden | Why | Enforced by |
|---|---|---|
| `double`/`float` for money | P1 | `-Werror` + ArchUnit |
| Money arithmetic in React components | P1, F9 | ESLint ban |
| `@Transactional` outside `application` | B15 | ArchUnit |
| jOOQ row types outside `adapter/persistence` | B16 | ArchUnit |
| Controller injecting a repository | B12 | ArchUnit |
| Catch-all `common`/`utils` module | B6 | Review + cycle test |
| Cross-module table joins in application code | B17 | Review + read models |
| A second backend language | P7 | ADR required |
| Pre-1.0 frontend dependency | P9 | Dependency policy gate |
| `double`/`float` for an FX rate | P12, B19 | ArchUnit + jqwik |
| Multiplying an amount by a rate outside `libs/fx` | B19 | ArchUnit |
| Reading `fx_rates`/`fx_quotes` outside `libs/fx` | B19 | ArchUnit |
| `Intl.NumberFormat` applied to a `number` amount | P1, F11 | ESLint + property test I5 |
| `parseFloat` on user amount input | P1, F12 | ESLint |
| A user-facing string literal in a component | F10 | Catalog check |
| Localized prose in an API response | P13, B20 | ArchUnit + review |
| Summing minor units across currencies | P1, ADR 0012 | Review; totals are per currency |
| A headline total without a stated reporting currency | ADR 0013 | Review |
| Weakening L6 to allow a mixed-currency journal entry | ADR 0012 | Review; L6 is deliberately intact |
| Hand-editing the generated client | C1 | C3 diff gate |
| `useEffect` data fetching | F6 | ESLint rule |
| Deep import into a feature's internals | F2, F8 | ESLint boundaries |
| `DELETE` on ledger tables | P3 | **Database grants** |
| Mutable balance columns as source of truth | P3 | Review; balances are projections |

**NOTE:** the strongest of these are enforced at the **database** level too - the application's DB
role has **no `DELETE` or `UPDATE` grant** on `journal_entries` or `postings`. Append-only becomes
physics, not policy.

---

## 12. ADR process

Any change to a **RULE**, the module graph, the stack, or P1-P11 requires an ADR.

```
docs/adr/NNNN-short-title.md
  Status: proposed | accepted | superseded by NNNN
  Context
  Decision
  Consequences (including what becomes harder)
  Alternatives considered - and why rejected
```

Seed ADRs: `0001-non-custodial-architecture`, `0002-java-spring-boot-single-backend`,
`0003-openapi-as-contract`, `0004-append-only-ledger`,
`0005-netting-algorithm-and-optimality-guarantee`, `0006-tax-as-configuration`,
`0007-tanstack-stable-only-frontend`, `0008-group-as-shard-key`,
`0009-spring-modulith-modular-monolith`, `0010-crypto-shredding-for-erasure`,
`0011-netting-must-not-increase-tax`, `0012-multicurrency-netting-per-currency`,
`0013-exact-fx-rates-and-residual`, `0014-localization-and-locale-handling`.

**NOTE:** ADR 0012, 0013 and 0014 are `accepted` as decisions but **not yet implemented**. They are
recorded first so that the contract and the schema can be shaped by them from the start, rather than
being reshaped around them later. Section 13 says when each lands.

**NOTE:** ADRs record *why the rejected alternatives were rejected*. That is the part future
maintainers actually need.

---

## 13. Roadmap to module mapping

| Phase | Deliverable | Modules touched |
|---|---|---|
| 0 | Decisions, ADRs, legal stance | `docs/*` only |
| 1 | MVP ledger + netting preview, **with i18n scaffolding from the first screen** | `libs/kernel`, `libs/ledger`, `apps/api`, `apps/web` (+ `locales/`) |
| 2 | Settlement cycle + reports on MockRail | `libs/netting`, `libs/rails`, `workers/settlement`, `apps/web` |
| 2.5 | **Cross-currency obligations** (ADR 0012, 0013): `libs/fx`, `libs/settlement`, per-currency partitions, FX funding state | `libs/fx` done, `libs/settlement` done (plan only), `libs/ledger`, `workers/settlement`, `apps/web`, `contracts/` |
| 3 | Real rail + tax engine + compliance hooks, **and remaining locales reviewed** | `libs/rails`, `libs/netting/internal/tax`, `platform/security`, `apps/web/locales` |
| 4 | Scale hardening | `platform/*`, `infra/*`, `workers/settlement` |
| 5 | Compliance depth + mobile | `apps/api` identity, new `apps/mobile` consuming `contracts/` |

**Two placement decisions worth defending.**

**i18n is Phase 1, not Phase 5.** Extracting strings after a UI exists means touching every component,
and the plural, parsing and formatting decisions have to be made before the first screen ships. It is
cheap now and a rewrite later (ADR 0014).

**Multicurrency is Phase 2.5, not Phase 1.** It depends on the settlement worker existing (for
`awaiting_fx_funding` and per-currency runs), and on a rate provider being selected. Doing it before
there is a settlement loop would mean building the FX funding state with nothing to attach it to.

**NOTE:** phase 5's mobile app is why `contracts/` is a top-level directory and why `shared/money`
and `shared/api` stay framework-agnostic. The contract and the money rules are the two things every
client must share.

---

## 14. Definition of done

A change is done when:

1. Its module's `README.md` still describes reality.
2. **RULE**s pass (ArchUnit / ESLint / CI gates).
3. New domain logic has property tests (jqwik / Vitest), not only example tests.
4. Money-touching code has a test proving there is no rounding or sign error.
5. The OpenAPI spec is updated and regeneration produces no diff.
6. New user-visible behaviour has a Playwright test.
7. New animation has a reduced-motion branch.
8. If a **RULE** changed, an ADR is accepted.
9. **Every new user-facing string exists in the `en` catalog**, and any shipped locale has no missing
   keys (F10, I1). Machine-translated text is marked unreviewed and is not used on money-moving
   screens.
10. **Money and dates are rendered through `shared/money` and `shared/i18n`**, and a test proves the
    formatted digits equal the digits derived from the integer minor units (F11, I5).
11. **If the change touches an amount, it states its currency**, and any total spanning currencies
    names its reporting currency (ADR 0012, 0013).
12. **A rate multiplication is inside `libs/fx`**, or it is not happening (B19).

---

## 15. The four mechanisms that actually make it modular

Everything above reduces to four enforcement points. This is the part worth remembering:

1. **Gradle `api` vs `implementation`** - compile-time visibility. Other modules physically cannot
   see what you did not export.
2. **`api/` + `internal/` packages + ArchUnit** - makes "public surface" real. RULE B10/B11 are what
   stop the codebase rotting into one big ball of mud.
3. **ArchUnit + ESLint boundaries in CI** - modularity is a *test*, so violating it breaks the build
   instead of triggering a code-review argument.
4. **Database grants** - no `DELETE`/`UPDATE` on ledger tables. Append-only is enforced below the
   application, where no developer can accidentally bypass it.

---

---

## Appendix A - Naming decisions and import discipline

Found while compiling and executing the pure core, and recorded here rather than left to tribal memory.

| Decision | Why |
|---|---|
| `io.reductxn.ledger.api.Currency` deliberately shadows `java.util.Currency` | Ours carries a minor-unit exponent, which is exactly what formatting needs. The JDK type requires a locale-driven lookup and is not the model we want at the centre of a ledger. |
| **RULE N1** - no wildcard imports in reductxn code | A wildcard `java.util.*` import makes `Currency` ambiguous and stops the build. Explicit imports also make a file's dependencies readable and keep ArchUnit failure messages precise. |
| No other domain type takes a name that already exists in `java.util` or `java.time` | Ambiguity multiplied across modules is how a domain language erodes. |
| Error codes are lower case, dot-separated and namespaced by the owning module | `fx.rate_stale`, `ledger.currency_mismatch`. A namespace is what makes a collision impossible rather than unlikely, and what makes a code attributable in a log. Enforced by `DomainCodes` and `DomainErrorCodeTest`. |

**NOTE:** RULE N1 is now gated, by the `forbidWildcardImports` verification task in `build-logic`,
alongside the RULE P1 floating-point guard. Both scan sources and fail the build, so a rule that only
lives in a document is no longer the situation for either.

---

## Appendix B - Verified state

**The Gradle build runs green.** `./gradlew clean build` succeeds, executing 99 tests, none failing:

| Suite | Tests | What it proves |
|---|---|---|
| `MoneyTest` | 7 | Exact integer money: arithmetic beyond 2^53, overflow throws, currencies never mix |
| `GreedyNettingTest` | 6 | Worked example, flat members, empty group, zero-amount obligations, and the sparse case that must settle directly |
| `GreedyNettingPropertyTest` | 8 | N1, N2, N3, N4, N6, N7, N8, N9 over generated inputs. Every assertion is labelled with its property id. N5 is *not* here: it compares an optimizer against the baseline and no optimizer exists (netting.md section 4) |
| `FxRateTest` | 8 | A rate cannot exist malformed: no self-rate, no non-positive numerator, bounded scale, expiry after quote |
| `FxConverterTest` | 18 | The spec's worked examples, both signs of the residual, the zero-rounding refusal, the overflow boundary, quote locking and quote magnitudes |
| `FxConverterPropertyTest` | 9 | X1-X6 over generated inputs. X2 checked against an independent `BigInteger` oracle |
| `SettlementPlannerTest` | 14 | ADR 0012's worked example, the clearing split, per-currency tax, order independence, refused input |
| `SettlementPlannerPropertyTest` | 6 | **X7** and the composition properties: zero-sum including `fx_clearing`, partition completeness, the two legs netting to the clearing position, reproducibility |
| `ModuleBoundaryTest` | 16 | RULEs B1-B5, B8, B10, B11, B18, B19, B21 |
| `ArchUnitVisibilityTest` | 1 | That the architecture rules above are not vacuous |
| `DomainErrorCodeTest` | 1 | That every error code is well formed, namespaced by its own module, unique, and declared as a constant |
| `RuleCoverageTest` | 2 | That every RULE in the plan *or the specs* is enforced or explicitly deferred, and that every claimed enforcement resolves to something that actually runs |
| `PropertyCoverageTest` | 3 | That every property in the specs is labelled by a test assertion or explicitly deferred, and that no test labels a property no spec defines |

All 16 architecture rules are verified non-vacuous, and that verification is automated rather than
something a reviewer has to notice. See the constraint table below.

**Coverage is checked for behaviour as well as structure.** `S1-S5` - quorum, idempotency, partial
application and the FX funding rule - are declared in `docs/specs/settlement.md` rather than in this
plan, and the first version of `RuleCoverageTest` read only this file and reported full coverage while
five rules had no decision at all. It reads the specs now.

Properties are held to the same standard by `PropertyCoverageTest`, which detects coverage rather than
declaring it: a property counts as covered when a test labels an assertion with its id
(`.as("X7: ...")`), a convention the property tests already followed. Nothing has to be maintained for a
covered property, so only the exceptions need an entry - and `N5`, all of ledger's `L1-L9` and all of
i18n's `I1-I7` have one, with reasons. That is 17 decisions that were previously invisible.

**Two guards here are not ArchUnit rules, and both matter as much:** `forbidFloatingPointMoney` and
`forbidWildcardImports` are Gradle verification tasks in `build-logic`, so they run for every module
rather than only for the one with architecture tests. RULE N1 had been recorded as "not yet gated"
since it was written; it is gated now.

**What is designed versus what exists.** This distinction matters more than usual after a design-heavy
change, so it is stated explicitly:

| Area | Decision | Code | Notes |
|---|---|---|---|
| Netting, tax, ledger, money | ADR 0004, 0005, 0006, 0011 | **Implemented, tested** | 21 tests green |
| FX rates and the residual | ADR 0013 | **Implemented, tested** | 35 tests green. `libs/fx` owns the rate type, the exact conversion and `FxQuote` |
| Per-currency composition | ADR 0012 | **Implemented, tested** | 20 tests green. `libs/settlement` partitions a cycle, composes the per-currency plans, and derives the external FX requirement |
| RULEs B3, B4, B5, B10, B18, B19, B21 | ADR 0012, 0013 | **Enforced** | 15 ArchUnit rules, all verified non-vacuous. B3 and B4 had been stated in section 5.1 and never enforced |
| `fx_clearing` postings | ADR 0012 | **Not implemented** | Nothing books the entry pair to the ledger. The plan computes the clearing position; the ledger never hears about it |
| Funding state, per-currency runs | ADR 0012 | **Not implemented** | Needs `workers/settlement`, which has no sources |
| Localization | ADR 0014 | **Not implemented** | No catalogs, no `shared/i18n`, no locale resolution |
| RULEs B20, F10-F12 | ADR 0014 | **Not enforced** | Listed in section 8 as gates, not yet written |

So the honest position is narrower than "multicurrency is done", and it has moved: **the plan is done
and proven; the postings are not.** A cycle can be partitioned, netted per currency, and reported with
its external FX requirement quantified per currency, and X7 is asserted against generated cycles.

What remains is the ledger side. `SettlementPlan` says what must move and what the group must find from
outside; nothing yet writes the two linked journal entries, nothing consumes the residual fraction, and
no run can be gated on funding. Those need `apps/api` and `workers/settlement` to have sources, which
they do not.

The next implementation step is the ledger side: booking the two linked entries per cross-currency
obligation, booking the residual fraction to `fx_clearing`, and gating a run on its funding. All three
need `apps/api` and `workers/settlement` to exist. For localization it is the Phase 1 scaffolding,
before the first screen exists to retrofit.


Toolchain constraints discovered by actually running the build. All are now pinned with reasons:

| Constraint | Why |
|---|---|
| **Gradle >= 9.1** | Java 25 support for *running Gradle* begins at 9.1. Gradle 8.14.x tops out at Java 24. This project uses 9.7.1. |
| **ArchUnit >= 1.5.0** | ArchUnit 1.x before 1.5.0 cannot read Java 25 bytecode (class file major version 69). It imports zero classes, so every rule fails as `emptyShould` - or, if `failOnEmptyShould` is ever disabled, **passes silently while enforcing nothing**. Verified by importing 30 classes explicitly. |
| **No `includeEngines(...)` on Test tasks** | Filtering by engine name silently excluded the ArchUnit engine (id `archunit`), so the architecture rules did not run at all and the task reported "did not discover any tests". |
| **ArchUnit package identifiers are not regex** | `(kernel|ledger|...)` matches nothing. Use `resideInAnyPackage(...)`. |
| **Every module a rule mentions must be on `platform/testing`'s classpath** | Otherwise ArchUnit imports none of its classes and the rule checks nothing. This is the *silent* form of the bug above: the subject set stays non-empty, so `emptyShould` never fires, while the `should` clause names a package ArchUnit has never seen. It produced a completely bogus green result for `b19` - a rule asserting no module depends on FX, while FX was invisible. Now caught by `ArchUnitVisibilityTest`, which was itself verified by making it fail on purpose. |
| **A rule can only forbid what it can see** | `b21` lists `apps/api` and `workers/settlement` as forbidden dependencies, but those have no sources, so ArchUnit has no classes to match and a violation would pass unnoticed. The rule is not vacuous - its subject resolves and is evaluated - but two of its exclusions are inert until the deployables exist. Stated in the rule itself so nobody reads a green tick as more than it is. |

The pure core also still compiles standalone with plain `javac` (no Gradle, no Spring), which is the
property that lets an auditor reproduce a settlement in isolation (principle P4).

ADR 0011 is implemented. The engine builds both the netted plan and the direct one, returns
whichever costs less, and reports a saving that cannot go negative. That changed the wire contract:
`contracts/openapi/reductxn-api.yaml` gained `pairwise` in the algorithm enum, edited before the code
(RULE C1).

ADR 0013's arithmetic is implemented in `libs/fx`: the scaled-integer rate, the exact conversion, the
fractional residual, and the fail-closed quote lock. Writing it out before implementing it corrected
the contract twice - `FxQuote` carried the residual as a whole minor unit, which cannot represent
`-0.096`, and the spec's bounds were stated as three independent limits that multiplied to 10^35. Both
are fixed. Measured against the compiled engine, the spec's worked examples reproduce exactly:

```
JPY  50,000  ->  2,795,200 INR minor        (INR 27,952.00)
JPY  50,001  ->  2,795,256 INR minor, residual -0.096
RUB 100,000  ->    102,410 USD minor        (USD 1,024.10)
INR  27,952  ->     50,000 JPY minor        (round trip)
```

**Error codes were inconsistent, and are now not.** `libs/fx` returned dotted, namespaced codes
(`fx.rate_stale`) matching the contract's examples and the ICU catalog keys in ADR 0014, while the
pre-existing `CurrencyMismatchException` in `libs/ledger` returned a bare `CURRENCY_MISMATCH`. The
contract is authoritative (P5), so the older exception was the one to change, and it did.

The delay was not about difficulty, it was about the absence of a check. The disagreement was recorded
as a comment in `FxException` and left there, because a comment cannot fail a build. The fix came with
the thing that makes it stay fixed:

- **`DomainCodes` in `libs/kernel`** states the convention once: lower case, dot-separated,
  namespaced by the owning module.
- **Every code is a `public static final String CODE`.** Not decoration - it is what makes codes
  *enumerable*. The error-code parity gate (I3, section 8) has to know every code the server can emit
  in order to check it against `en/errors.json`, and a code hidden inside a method body cannot be
  enumerated, so it cannot be gated.
- **`DomainErrorCodeTest`** enforces well-formedness, module-ownership of the namespace, uniqueness,
  and the presence of the constant - each verified by sabotage.

`obligation.currency_mismatch` used to appear as the example in the contract and in the i18n spec. It is
now `ledger.currency_mismatch`, because the convention that four `fx.*` codes had already established is
that the namespace is the owning module, and two spellings of one error is how a client catalog grows a
key nobody can find.

**Not yet verified:** `apps/api`, `workers/settlement`, `apps/web` and the `platform/*` modules have no
sources. `apps/api`, `workers/settlement`, `platform/observability`, `platform/persistence` and
`platform/security` are already declared in `settings.gradle.kts`, so they are real (empty) Gradle
projects and a module boundary rule already covers them - they simply contain nothing yet. This
paragraph previously said they were not yet declared, which was wrong. Those are Phase 1 work.


*End of master plan. Changes to this document follow section 12.*
