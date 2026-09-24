# reductxn - Master Plan & Architecture

**Status:** Draft v0.1 · **Last updated:** 2026-09-24
**Audience:** engineers, reviewers, security & compliance, external auditors
**Companion docs:** `docs/adr/*`, `docs/specs/netting.md`, `docs/specs/tax-rules.md`, `docs/specs/ledger.md`, `docs/specs/settlement.md`, `docs/THREAT-MODEL.md`

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
                        +---+-----------+-------------+-------+
                            |           |             |
            +---------------+           |             +-----------------+
            v                           v                               v
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
|   +-- rails/                      # PaymentRail port + adapters
|
+-- apps/
|   +-- api/                        # Spring Boot 4.1.1 modular monolith
|   +-- web/                        # React 19 + Vite + TanStack + GSAP
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
              +----------------+----------------+
              |                |                |
         +---------+      +----------+    +----------+
         | ledger  |      | netting  |    |  rails   |
         +----+----+      +-----+----+    +-----+----+
              ^                 ^               ^
              +--------+--------+---------------+
                       |
         +-------------+---------------+     +---------------------+
         |         apps/api            |     |  workers/settlement |
         +-------------+---------------+     +----------+----------+
                       ^                                ^
                       +-------------+------------------+
                                     |
                             +-------+--------+
                             |   platform     |
                             +----------------+
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

**NOTE:** RULE B6 is the one people fight. When settlement needs "the same" logic as the API, the
answer is never a `common` module containing everything - it is to extract the actual concept into
`libs/*` under a name that describes it.

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
    +-- money/            # formatting/parsing of minor units
    +-- ui/               # design system primitives
    +-- gsap/             # timelines, useGSAP hooks, matchMedia presets
    +-- lib/              # date, result, invariant helpers
    +-- config/           # env parsing (Zod-validated)
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
| Module graph | ArchUnit + Spring Modulith | RULE B1-B17 violated |
| Import boundaries | `eslint-plugin-boundaries` | RULE F1-F9 violated |
| Money discipline | lint ban on `double`/`float` in domain + ESLint ban on division by 100 | P1 violated |
| Spec sync | codegen diff check | RULE C3 violated |
| Ledger invariants | jqwik property tests | A journal entry does not balance |
| Netting correctness | jqwik + golden fixtures | Sum of nets != 0, or a member ends negative |
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
`0009-spring-modulith-modular-monolith`, `0010-crypto-shredding-for-erasure`.

**NOTE:** ADRs record *why the rejected alternatives were rejected*. That is the part future
maintainers actually need.

---

## 13. Roadmap to module mapping

| Phase | Deliverable | Modules touched |
|---|---|---|
| 0 | Decisions, ADRs, legal stance | `docs/*` only |
| 1 | MVP ledger + netting preview | `libs/kernel`, `libs/ledger`, `apps/api`, `apps/web` |
| 2 | Settlement cycle + reports on MockRail | `libs/netting`, `libs/rails`, `workers/settlement`, `apps/web` |
| 3 | Real rail + tax engine + compliance hooks | `libs/rails`, `libs/netting/internal/tax`, `platform/security` |
| 4 | Scale hardening | `platform/*`, `infra/*`, `workers/settlement` |
| 5 | Compliance depth + mobile | `apps/api` identity, new `apps/mobile` consuming `contracts/` |

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

**NOTE:** RULE N1 is not yet gated in CI. Until it is, it is a GUIDE enforced in review.

---

## Appendix B - Verified state at time of writing

The pure core was compiled and executed as part of producing this document:

- `javac` compiled all `libs/*` main sources with no errors or warnings.
- The worked example in `docs/specs/netting.md` section 5 was executed: 4 obligations netted to 3
transfers, tax 1.50 against a pairwise 2.00, saving 0.50.
- 5,000 generated groups (104,009 obligations) satisfied N1, N2, N3, N4 and N7.
- Money behaviour confirmed: exact arithmetic beyond 2^53, overflow throws rather than wraps,
currency mismatch throws, ad valorem floor and cap behave, and a zero base yields zero tax.

What is **not** yet verified: the Gradle build itself. The `gradlew` wrapper has not been generated,
and `apps/api`, `workers/settlement` and `apps/web` have no sources yet. Those are Phase 1 work.

*End of master plan. Changes to this document follow section 12.*
