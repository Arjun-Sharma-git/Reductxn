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
obligation can be recorded (ADR 0013, point 6 - fail closed). Since ADR 0027 a provider's document is
read by a scheduled ingestion into `fx_rates` rather than fetched during a request, so an outage is a
growing staleness rather than an immediate refusal - but it is still an availability incident for a
subset of traffic, and it needs its own runbook and staleness alerting rather than being discovered as a
spike in rejected requests. The freshness bound (`reductxn.rates.max-age`) is the number such an alert
should be built around, because it is the point at which a stored rate stops being lockable.

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
|   +-- planning/                   # per-currency plans, the fx_clearing position (ADR 0012)
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
**RULE B21** - `libs/planning` depends only on `libs/kernel`, `libs/ledger`, `libs/netting` and
`libs/fx`. It is the only module permitted to compose per-currency plans, and the only place
`fx_clearing` is modelled. It is pure: no I/O, no clock, no provider.

**RULE B22** - `libs/booking` depends only on `libs/kernel`, `libs/ledger`, `libs/planning` and
`libs/fx`. It composes the entries a commitment books out of what planning already decided, so it may not
run the netting engine, move anything on a rail, or reach into a deployable. Like B21 it exists because of
B6: the API books an obligation when it is recorded and the worker books a run when it executes, and those
two may never depend on each other.
answer is never a `common` module containing everything - it is to extract the actual concept into
`libs/*` under a name that describes it.

**`libs/planning` is the first working example of that rule**, so the reasoning is recorded rather
than left in a commit message. Partitioning a multicurrency cycle is needed by two modules that may
not see each other (B6): `apps/api`, to preview what a cycle would cost, and `workers/settlement`, to
build the run. Leaving it in the worker would have forced the API to reimplement it, which is two
implementations of the same financial arithmetic drifting apart. And it could not go in
`libs/netting`, which may only depend on kernel and ledger (B3) - giving the netting engine FX
awareness is exactly what ADR 0012 forbids, because its properties depend on one currency at a time.
So it moved *down* into a pure module that both deployables consume.

The naming used to be a trap. Both modules were called `settlement`, so the only thing distinguishing
them was the path, and every sentence about "settlement" needed the reader to work out which one was
meant. The distinction that actually matters is **plan versus run**, and the names now carry it:

| Module | Owns |
|---|---|
| `libs/planning` | **What** must be settled: the per-currency plan, the partitions, the `fx_clearing` position, the external FX requirement. Pure and deterministic |
| `libs/booking` | **What that becomes in the ledger**: the entries a commitment produces, their currencies, their link and their chain. Pure, and one more example of RULE B6 |
| `workers/settlement` | **Executing** it: the run lifecycle, quorum, idempotency, rail submission, funding state |

**The worker kept the name, because "settlement" already meant the run everywhere it appeared.** The
tables it owns are `settlement_cycles`, `settlement_runs` and `settlement_transfers` (RULE B17); the
contract reserves the `settlement.*` error namespace, and the one known example is
`settlement.fx_unfunded`, which is the funding gate (S4) - a run concern; `docs/specs/settlement.md` is
about quorum, idempotency, rails and funding, all of the run. The library was the outlier, so the
library is what moved, to a name that says what it produces.

**It was not only about readability.** Two Gradle modules cannot share a package root, and the package
root here is the module's last path segment. With both at `io.reductxn.settlement`, every ArchUnit rule
written against `io.reductxn.settlement..` would have matched **both** modules - including B21, which
says settlement depends only on kernel, ledger, netting and fx. The worker must depend on rails and on
persistence. A boundary rule that matches two modules is not a boundary rule, and it would have failed
the moment the worker got its first source file, for a reason that looked like a code problem.

Done while the worker is still empty and the library is 12 files, which is the whole argument for
deciding it now: after Phase 2 it would be a migration.

Recorded as **ADR 0015**, because section 12 requires an ADR for a change to the module graph and for a
change to a RULE that names a module, and B21 names this one.

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

**Status:** the layout exists and the rules below are enforced over it, but what sits in each layer is
deliberately partial. `web` holds one controller plus the error mapping, `application` holds one use case,
`port` holds three interfaces, and `adapter` holds development doubles rather than a persistence adapter.
The controllers this tree names - identity, groups, obligations, settlement, tax - are the plan, not an
inventory.


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
| `settlement_cycles`, `settlement_runs`, `settlement_partitions`, `net_positions`, `settlement_transfers`, `settlement_run_obligations`, `settlement_decisions`, `settlement_fx_fundings` | `workers/settlement` - the run |
| `tax_rules`, `tax_assessments` | `libs/netting` - tax |
| `fx_rates`, `fx_quotes`, `currencies` | `libs/fx` (schema per `docs/specs/fx.md`) |
| ICU message catalogs | `apps/web` - in the build artefact, never in the database |

**NOTE:** `currencies` is owned by `libs/fx` rather than `libs/ledger` because the minor-unit exponent
is what conversion needs first, and because a currency is reference data, not a ledger concept.

**Migrations live beside the owner, and versions are namespaced by owner.** Flyway keeps one version
sequence per schema however many locations it reads, so a `V1` in `apps/api` and a `V1` in `libs/netting`
are "more than one migration with version 1" and the application refuses to start. Each owner therefore
holds a version range - `apps/api` owns `1xx`, `libs/netting` owns `2xx`, `libs/ledger` owns `3xx`,
`libs/fx` owns `4xx` - and `SchemaOwnershipTest` checks
both halves of the rule: no table created by two modules, and no version outside its owner's range. The
range is also what makes cross-module ordering explicit instead of accidental. Enforced now, and the
enforcement arrived before the second module's migration did, which is why the collision was a test failure
rather than an environment.

**NOTE:** two of those ranges arrived with the first write path. `libs/ledger`'s `V301` creates
`journal_entries` and `postings`, and `libs/fx`'s `V401` creates `currencies` - the latter because the
contract's `Money` carries a currency code and no exponent, so nothing can be recorded until the exponent can
be resolved. The version range map in `SchemaOwnershipTest` gained two entries, which is the point of it being
a map rather than a convention: a module that starts owning tables has to say so.

**NOTE:** the cross-currency slice added `V402` (`libs/fx`'s `fx_rates` and `fx_quotes`) and `V103`
(`apps/api`'s `obligations.quote_id`). `V402` is a second migration for `libs/fx` rather than an edit to
`V401`, for the reason the version scheme exists at all: a migration is applied history, and `obligations`
gaining a column is a new file even though the table is only a week old.

**NOTE:** `workers/settlement` owns `5xx`, and needed to say so the moment it owned a table. The ranges are now
`apps/api` 1xx, `libs/netting` 2xx, `libs/ledger` 3xx, `libs/fx` 4xx and `workers/settlement` 5xx, and
`SchemaOwnershipTest`'s map has one entry per owner. A module that starts owning tables has to add itself, which
is the point: the alternative is discovering the collision when the application will not start.

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
          .should().dependOnClassesThat().resideInAPackage("io.reductxn.planning..")
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
| Mutations | `features/<x>/mutations.ts`; optimistic + rollback for obligations - **real since ADR 0029**, and the idempotency key belongs to the attempt rather than to the click (P8) |
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
| Module graph | ArchUnit + Spring Modulith | RULE B1-B22 violated. **Live**: 28 ArchUnit rules plus the schema-ownership test, all verified non-vacuous. Spring Modulith is not wired: ArchUnit covers the module graph, but the living documentation it would generate does not exist |
| Rule coverage | `RuleCoverageTest` - **live** | A RULE declared in this plan *or in `docs/specs`* is neither enforced nor explicitly deferred, a coverage entry is stale, or a claimed enforcement does not resolve to something that runs |
| Property coverage | `PropertyCoverageTest` - **live** | A property declared in a spec has no assertion labelled with its id and no recorded reason, or a test labels a property that no spec defines |
| Import boundaries | `eslint-plugin-boundaries` - **live for F1-F3, F5, F8-F12** | RULE F1-F12 violated. `apps/web/eslint.config.js` holds the arrow `routes -> features -> entities -> shared` as an error, `fetch` banned outside `shared/api`, JSX text banned, `Intl.NumberFormat`/`parseFloat`/division-by-100 banned in the money path. F4 (thin routes) stays review: no rule can see "one component and no logic" |
| Money discipline | lint ban on `double`/`float` in domain + ESLint ban on division by 100 | P1 violated. **Live**: the `forbidFloatingPointMoney` task in `build-logic` |
| Import style | `forbidWildcardImports` task - **live** | A wildcard import appears (RULE N1) |
| Tracked source | `forbidIgnoredSources` task - **live** | A source file that `.gitignore` excludes, so it compiles and tests green locally and is absent from every clone |
| FX exactness | jqwik X1-X7 - **live** | A conversion is non-deterministic, the residual is not booked, or the round trip drifts |
| FX isolation | ArchUnit - **live** | A rate is multiplied anywhere outside `libs/fx` (B18, B19). Non-vacuous, and guarded against becoming vacuous |
| Error-code convention | `DomainErrorCodeTest` - **live** | A code is malformed, namespaced by the wrong module, duplicated, or not declared as a constant |
| Locale coverage | `tools/check-catalogs.mjs` - **live** | A key is missing versus `en` (unless the locale is flagged `complete: false`), a key is unused, a literal appears in a component (F10, I1, I2). The literal half is ESLint rather than this tool, and the one exception is the product's name, which is configuration |
| Error-code parity | `tools/check-catalogs.mjs` - **live, both directions** | A server error `code` has no catalog entry, or vice versa (I3). The server's codes are *collected from the Java sources* rather than listed by hand, which is the half that was missing: every `DomainError` declares its code as a constant, so the set is enumerable. `client.*` and `amount.*` are client-owned namespaces and are excluded from both directions |
| Locale money safety | Vitest property tests - **live** | Formatting changes the digits, or a `float` enters the format/parse path (F11, F12, I4, I5). Six hundred generated amounts across `en-IN`, `en-US`, `ru` and `ja`; it has already found three real defects |
| Locale plural completeness | `tools/check-catalogs.mjs` - **live** | A plural message is missing a category its locale requires (I7), with the categories read from `Intl.PluralRules` rather than from a table |
| Spec sync | `.github/workflows/ci.yml` - **live, both artefacts** | A generated artefact committed in the repository is not what the source produces (RULE C3): `pnpm codegen` then a diff of `src/shared/api/schema.d.ts`, and the build then a diff of `src/routeTree.gen.ts`. Both were shown to fail on drift and pass when clean (ADR 0031) |
| Contract coverage | `ContractCoverageTest` - **live, three directions** | The contract promises an operation no handler answers and nobody owns; an operation recorded as a gap is now served (a stale entry reads as coverage); or a controller serves an operation the contract does not declare. The seventeen known gaps are named with the screen or decision that owns each, and it is the check the seven-slice members read would have failed on the day it was written (ADR 0038) |

| Ledger invariants | jqwik property tests - **live for L1, L2, L4, L6, L9**, the rest deferred with reasons | A journal entry does not balance, or an FX link is unpaired (L9) |
| Netting correctness | jqwik + golden fixtures | Sum of nets != 0 per currency, or a member ends negative |
| Settlement idempotency | Testcontainers replay test | Double execution changes state |
| Coverage | JaCoCo - `libs/*` **95%**, overall **75%** | Below threshold |
| Frontend | Vitest + Playwright - **live** | Any failure. `pnpm check` (typecheck, lint, catalogs, unit, build) and `pnpm e2e` (the browser suite, including axe's contrast check) |
| Dependency policy | version catalogue check | Any pre-1.0 dependency in `apps/web` (P9). **Live by inspection rather than by tool**: every version in `apps/web/package.json` was resolved deliberately and the pins are recorded in ADR 0028 - a check that read the lockfile against a policy list would be the tool version of the same statement |
| CI | `.github/workflows/ci.yml` - **live** | A gate fails on a branch, or on `main`. Two jobs, each named after the command it runs: `api` (`./gradlew clean build` on Temurin 25, with the Docker Testcontainers needs) and `web` (pnpm from `packageManager`, Node 24, `pnpm install --frozen-lockfile`, then `pnpm check` and `pnpm e2e`). Every command was run from a fresh clone before the workflow was written, and the pipeline adds the one thing a local run cannot: an environment nobody has been warming up (ADR 0031) |
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
2. Expose it through the factory in `libs/rails/api` (`Rails`) and register it in the deployable that executes
   runs - `workers/settlement/bootstrap`, not `apps/api`. **This step was wrong until execution landed**: it said
   to register the adapter in `apps/api/adapter/rail/`, which was written when `apps/api` was the only deployable.
   Rails are called by whoever submits a run's transfers, and that is the worker (RULE B6 keeps `apps/api` from
   knowing a rail exists at all).
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

**Corrected after ADR 0027, because the recipe described a package that RULE B16 had already made impossible and a
dependency this repository does not have.**

1. Implement the port in `apps/api/adapter/feed/` if it *fetches* something (`FxRateFeed`, the port owned by
   `libs/fx/api`), or in `apps/api/adapter/persistence/` if it *reads a table* (`FxRateProvider`). RULE B16 pins
   every jOOQ type to `adapter/persistence`, so the package is a consequence of what the adapter does, not a choice.
2. Derive the pairs in `libs/fx` (`CrossRates`), never in the adapter: a rate divided by a rate is rate arithmetic
   (RULE B19).
3. Configure the provider's location (`reductxn.rates.feed`, a URL or a file path) and the freshness bound
   (`reductxn.rates.max-age`, which depends on how often the provider publishes - days for a daily reference feed,
   seconds for a stream). Failover order is still open (`docs/specs/fx.md` section 11).
4. Test the provider's **real document** as a fixture for the parser, and ingest it from a file for the end-to-end
   path - no WireMock, no network, and the contract under test is the one that ships. Add a test that a stale quote
   **refuses** a cross-currency obligation rather than guessing (ADR 0013, point 6).
5. **No changes** to `libs/ledger`, `libs/netting`, the settlement worker, or the UI.

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
`0013-exact-fx-rates-and-residual`, `0014-localization-and-locale-handling`,
`0015-planning-computes-the-plan-settlement-executes-the-run`.

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
| 1 | MVP ledger + netting preview, **with i18n scaffolding from the first screen** | `libs/kernel`, `libs/ledger`, `apps/api`, `apps/web` (+ `locales/`) - **`apps/web` started (ADR 0028): one screen, the five layers, the i18n scaffolding and its gates** |
| 2 | Settlement cycle + reports on MockRail | `libs/netting`, `libs/rails`, `workers/settlement`, `apps/web` |
| 2.5 | **Cross-currency obligations** (ADR 0012, 0013): `libs/fx`, `libs/planning`, per-currency partitions, FX funding state | `libs/fx` done, `libs/planning` done (plan only), `libs/ledger`, `workers/settlement`, `apps/web`, `contracts/` |
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
| **Module names say *plan* or *run*, not both** - `libs/planning` computes the plan, `workers/settlement` executes the run | Two modules with the same name make every sentence about them ambiguous, and two Gradle modules cannot share a package root without every ArchUnit rule written against it matching both. Full reasoning in section 5.1. |
| **RULE N1** - no wildcard imports in reductxn code | A wildcard `java.util.*` import makes `Currency` ambiguous and stops the build. Explicit imports also make a file's dependencies readable and keep ArchUnit failure messages precise. |
| No other domain type takes a name that already exists in `java.util` or `java.time` | Ambiguity multiplied across modules is how a domain language erodes. |
| Error codes are lower case, dot-separated and namespaced by the owning module | `fx.rate_stale`, `ledger.currency_mismatch`. A namespace is what makes a collision impossible rather than unlikely, and what makes a code attributable in a log. Enforced by `DomainCodes` and `DomainErrorCodeTest`. |

**NOTE:** RULE N1 is now gated, by the `forbidWildcardImports` verification task in `build-logic`,
alongside the RULE P1 floating-point guard. Both scan sources and fail the build, so a rule that only
lives in a document is no longer the situation for either.

---

## Appendix B - Verified state

**The Gradle build runs green.** `./gradlew clean build` succeeds, executing 394 tests, none failing:

| Suite | Tests | What it proves |
|---|---|---|
| `MoneyTest` | 7 | Exact integer money: arithmetic beyond 2^53, overflow throws, currencies never mix |
| `PostingTest` | 4 | L2: an amount is a magnitude and the direction carries the sign |
| `JournalEntryTest` | 12 | L1, L6 and the FX linkage pairing, refused on construction; the hash's shape, and a versioned encoding that length-prefixes every free-form value |
| `JournalEntryPropertyTest` | 6 | L1, L2, L4 and L6 over generated entries, including that any single-field change changes the hash |
| `EntryChainTest` | 6 | L4: an untouched chain verifies, and a rewritten entry is caught by the entry after it |
| `FxLinkageTest` | 7 | L9: pairing across chains, in both directions, within a group |
| `BalancesTest` | 6 | L5's positive half - positions projected from postings, order-independently - and the measurement L8 needs |
| `GreedyNettingTest` | 6 | Worked example, flat members, empty group, zero-amount obligations, and the sparse case that must settle directly |
| `GreedyNettingPropertyTest` | 8 | N1, N2, N3, N4, N6, N7, N8, N9 over generated inputs. Every assertion is labelled with its property id. N5 is *not* here: it compares an optimizer against the baseline and no optimizer exists (netting.md section 4) |
| `FxRateTest` | 8 | A rate cannot exist malformed: no self-rate, no non-positive numerator, bounded scale, expiry after quote |
| `FxConverterTest` | 18 | The spec's worked examples, both signs of the residual, the zero-rounding refusal, the overflow boundary, quote locking and quote magnitudes |
| `FxConverterPropertyTest` | 9 | X1-X6 over generated inputs. X2 checked against an independent `BigInteger` oracle |
| `CrossRatesTest` | 10 | The rates a deployment needs, derived from a provider that quotes one base: both directions of every pair among the currencies it knows, a cross the provider never published (109.2605 / 179.70 = 0.60801614, hand-computed), each direction rounded independently rather than one inverted from the other, a pair against the publication's own base being the published value re-expressed at our scale, a derived pair saying so in its `source` while a published one keeps the provider's name, the whole thing all-in with the deployment's freshness window, and four refusals - a currency the provider did not quote, a freshness window that would expire a rate before it existed, a derived rate outside the range a rate may have, and a product that would leave a `long` |
| `CrossRatesPropertyTest` | 4 | The derivation's contract over generated publications: a derived rate is the exact ratio rounded to the nearest last place (checked against a `BigInteger` numerator, so the bound does not inherit the error it measures), a larger observation is never a smaller rate, the scale a publication is normalised to cannot change a derived rate (which is what makes one scale per document safe), and a derived rate is either inside the spec's bounds or refused |
| `SettlementPlannerTest` | 14 | ADR 0012's worked example, the clearing split, per-currency tax, order independence, refused input |
| `SettlementPlannerPropertyTest` | 6 | **X7** and the composition properties: zero-sum including `fx_clearing`, partition completeness, the two legs netting to the clearing position, reproducibility |
| `OpenCycleTest` | 4 | Opening a cycle on its own, which is the separation this slice exists for: a cycle exists with no run over it, opening twice is one row and the second call says which did the work, a cycle opened now is the cycle computed later - the two paths meeting on the group and the instant rather than on an id, which is the composition the whole design rests on - and one instant in two groups is two cycles |
| `ComputeOpenedCyclesTest` | 7 | The pass that replies to an opened cycle, against a container: a cycle somebody opened becomes a plan whose status is the group's next step, a cycle that already has one is not offered again (`one_run_per_cycle` is a constraint and a second plan would be a second answer), a group with nothing to settle is refused *and reported* and looked at again on the next pass - because an obligation recorded later can carry an instant inside that cycle - a refusal does not stop the cycles after it, the oldest request is served first, a pass is bounded by its batch, and a batch of zero is refused before anything is read |
| `ComputeOpenedCyclesFaultTest` | 1 | A plan that cannot be computed at all and what it must not do to the others: a planner that throws for one group (a delegating stub, not a mock) leaves the pass reporting a fault it names, while the plan the next cycle produced is still committed. The assertion is about the *commit* as much as the report - one transaction per cycle and none around the pass is what keeps a broken group from becoming every group's problem, since the cycle stays in the query |
| `ScheduledRunComputerTest` | 4 | The computer's cadence at its seam, without a database or a scheduler, as `ScheduledRunDriverTest` does for the driver's: a tick hands over the batch it was configured with, a tick with nothing to do is silent rather than failing, a tick survives a fault so the next one can happen, and a tick with outcomes returns |

| `ComputeRunTest` | 5 | The settlement worker's whole inbound surface, against a container: a single-currency cycle planned and left `awaiting_approval`, a cross-currency one left `awaiting_fx_funding` with the requirement reported per currency, a retry returning the same run and writing no second plan, a cycle with nothing in it refused rather than recorded as an empty run, and the plan's own transfers applied to the stored positions - which zeroes every participant in every currency (N2, per currency, read back from the rows) |
| `QuorumTest` | 9 | RULE S1's arithmetic, without a database: a majority of the run's own participants, every net debtor personally (including a member who owes in one currency and is owed in another), the clearing account counted as a participant and never as a member, a stranger's approval shown and not counted, and a stable report order |
| `FundingTest` | 6 | RULE S4's arithmetic, without a database: partial arrivals summing, each currency covered on its own, a surplus covering rather than being carried into another currency, and a funding nothing was waiting for reported rather than dropped |
| `DecideOnRunTest` | 8 | Quorum over a container: the members taken from the run's own plan, a majority that excludes the debtor who is paying being refused, a non-participant and the clearing account refused, a second answer refused, and a rejection stored with who made it |
| `RecordFxFundingTest` | 9 | The escaping half of `awaiting_fx_funding`: a cross-currency run blocked until *every* currency is covered, partial arrivals recorded with the shortfall reported, over-funding recorded, a currency the plan never asked for refused, and a retried funding recorded once |
| `ExecuteRunTest` | 11 | RULES S1-S4 against a container and a rail the test controls: nothing reaches the rail without quorum or funding, an approved and funded run confirms every transfer exactly once, a replay asks the rail for nothing new, a refused transfer fails the run while the confirmed ones stay confirmed, a rail that has not answered leaves the run `executing` and is *polled* rather than re-submitted, a rail that cannot be reached leaves the transfer `authorized`, a currency the rail cannot settle is refused before anything moves, a rejected run never executes, and a cycle that nets to nothing is `done` without a movement |
| `SettlementBookkeepingTest` | 6 | What a settled run leaves behind: one `settlement` entry per confirmed transfer carrying the rail reference, verifiable as a chain by `EntryChain`; a replay booking nothing; a refused transfer booking nothing while the confirmed one books; the whole cycle leaving every member flat and `fx_clearing` at zero per currency, read back from the stored postings with `Balances` (L8); a commitment a settled run discharged not open for a later cycle; and a failed run settling nothing, so its commitments stay open |
| `DriveRunsTest` | 9 | The driver, which is the only caller of `ExecuteRun` in a deployment: a run nothing is waiting on reaches `done` with no test touching the use case; a run that cannot progress is left exactly where it was with the gate's own reason and nothing reaches the rail; a run starts the moment its last fact arrives, because a pass is what notices; a transfer the rail accepted and never confirmed is resolved by a later pass (asked, not re-submitted); the same run is not driven twice inside the window; a finished run is never reconsidered; a named run ignores the window and is the operator's act; a pass is bounded by its batch size and takes the least recently attempted first |
| `SettlementCommandTest` | 20 | The operator's command line over real beans and a real database: no `--reductxn.command` leaves the worker running; `compute` opens a cycle, prints the plan and reports quorum; a group with nothing to settle is *refused* (`1`) rather than reported as empty; a missing option, a malformed uuid and an unknown command are *usage errors* (`2`); `fund` records an arrival, reports what is still required, and treats a repeated reference as one arrival; and an approved, funded run is settled to `done` from a shell in two commands - with approvals recorded through the port, which is the gap that test makes visible rather than hides. Since ADR 0024 and 0025 it also covers the report (`escalations` is silent when nothing is stuck, names the silent transfers with the state the rail left them in and says when it truncated, and names the `resolve` command that answers it), the other reason a transfer is stuck (with both policies at zero, a transfer a rail accepted and will not confirm is listed as `no_confirmation` with "the rail is answering"), and the resolution itself: a transfer resolved from a shell books a movement and completes the run, a missing `--reductxn.by` or a state only the worker can know is a usage error rather than a write (`countIn("journal_entries")` stays zero), and a second resolution is refused with the code and books nothing. Since ADR 0032 it also covers the two commands that separate opening a cycle from computing it: `open` records a cycle and computes nothing - `countIn("settlement_runs")` stays zero, which is the separation the slice exists for - opening the same cycle twice is `0` rather than a refusal and leaves one row, a missing `--reductxn.group` is a usage error, `compute-opened` plans what somebody opened and prints it, and a job that finds nothing to compute exits `0` because nobody having opened a cycle is not a failure |
| `StuckTransfersTest` | 11 | The report behind section 4.4's "or escalate" (ADR 0024, 0026), against the real database and a rail under the test's control: a silence seconds old is recorded but not reported, one past the policy is reported with the reason `no_answer`, the state the rail left the transfer in and an age measured from the first call of the spell rather than the last; a rail that keeps answering "still pending" is reported too (reason `no_confirmation`, age from the acceptance, no silence at all and a zero retry count) and two polls do not restart that age; an acceptance is kept as history when the rail finally confirms, and the transfer leaves the report; one listing carries both reasons from two runs, ordered by the instant each report measures; either policy alone pulls a transfer in, and neither implies the other; an answer ends both the silence and the report (and the run completes); nothing is ever declared decided on the strength of silence; a run that failed is not resumed by a later pass, and its stuck transfer is still reported because the report reads transfers rather than runs; and a `StuckTransfer` cannot be built from a row with no reason to be stuck (pending, no instant, or authorized with an acceptance) |
| `ResolveTransferTest` | 14 | The way back from that report (ADR 0025, RULE S6): a person's answer books the movement a confirmation books and completes the run; the rail is asked nothing and no other transfer is touched (`submissionsOf` and `polls()` both unmoved); an answer of "it did not move" books nothing (`countIn("journal_entries")` is zero) and ends the run as `failed`; a `submitted` transfer the rail accepted and went quiet *without* a failing call is resolvable even though it is in no report, and one the rail never acknowledged is resolvable too, because the worker's record of a submission is not the truth about money; an answer already stored is refused, and the refusal names who decided, when and on what basis, because the case it meets is an operator re-running after a slow response (a rail's own answer is refused the same way and names nobody - "a rail answered it"); a `pending` transfer and a run whose group rejected it have nothing to answer; both ids must be real (`transfer_not_found`, `run_not_found`, and no entry booked); one answer in a run with others outstanding leaves it `executing` and the last one completes it, which is `RunOutcome` - the rule execution uses - doing the same job for the second writer; a resolved transfer leaves the escalation report it came from; and a silent transfer inside a `failed` run is still resolvable while the run stays failed. Two of these pin decisions rather than mechanisms (the rail-never-acknowledged case and the not-yet-past-patience case), and one asserts that an unattributed assertion cannot even be constructed |
| `ScheduledRunDriverTest` | 6 | The cadence without Spring or a database: a tick asks for runs whose last attempt is older than the window (never for named runs), asks for whatever is stuck past either policy - both cut-offs in one read, since one report carries both reasons - and survives a fault in either half, including a report query that throws, which must not be reported as a failed settlement pass. A tick that finds nothing is silent rather than failing |
| `BackoffTest` | 3 | The wait between attempts as arithmetic rather than behaviour: doubling from the base, the cap as a floor that a transfer cannot drift past, a base of zero meaning "try again at once", and the three ways a policy that cannot be computed is refused |
| `RailRetryTest` | 5 | A rail that does not answer, and a rail that says no: an unanswered call is counted and its retry scheduled, the transfer is not offered again until its wait is over (a scheduled pass and a manual one alike), the wait doubles with each consecutive failure and stops at the cap, any answer resets the count and lets the run finish, and a refused instruction fails the transfer and the run at once with no later pass offering it again |
| `RecordedDecisionsTest` | 7 | The reading half of a crossing no module spans: a member's answer written with the API's own SQL, judged by the run's arithmetic. Two approvals settle a cycle with nothing else touching the worker; one is not a quorum; an approval from someone the run does not concern counts for nothing; an answer from the API and one from this module's use case are the same arithmetic; the run's rows refuse an answer from the clearing account; a refusal from the API leaves the run reading `awaiting_approval` until the next look writes `rejected` and moves nothing; and a stranger's refusal is recorded and inert |
| `BrokenRailTest` | 2 | A rail that has gone down: it is left alone rather than called by every pass (the detail says so, in the port's own vocabulary, with the cooldown in it), one probe per cooldown, a probe that fails restarts the cooldown, and a probe that succeeds closes the breaker and lets the run finish |
| `MockRailTest` | 5 | The only rail this build has: what it settles, that it confirms and returns the caller's reference, that a re-submission returns what it already holds (RULE S2 at the rail), and that an unknown reference or an unsupported currency is answered rather than ignored - the latter now with the classification attached, since a currency the rail cannot settle is a refusal that would be refused again |
| `CircuitBreakerTest` | 8 | The breaker against a scripted rail and a clock the test moves: calls go through while the rail answers, the rail is left alone after consecutive failures, one probe per cooldown closes it on an answer, a probe that fails restarts the cooldown, a terminal refusal never opens it (a rail with an opinion is not an outage), a rail that throws is treated as one that did not answer, and a policy that could not protect anything is refused |

| `ReductxnApplicationTest` | 1 | That the API's context starts against a container with every owning module's migration applied - `apps/api`'s obligations, `libs/ledger`'s entries and postings, `libs/fx`'s currencies, rates and quotes, `libs/netting`'s tax rules - which is the arrangement most likely to break silently, since a migration that never ran leaves a schema that looks migrated |
| `MigrationsConfigurationTest` | 2 | That the migration guard refuses to start when Flyway finds nothing, and delegates to migrate when it finds the fixture - against a real Postgres, in a schema of its own |
| `NettingPreviewApiTest` | 6 | The contract's preview through every layer, over rows written with SQL and read back through jOOQ: the single-currency shape, a multicurrency group reported as partitions rather than collapsed into one, a commitment a settled run discharged being excluded, an empty plan, framework errors as problem+json, and `asOf` meaning "open at that instant" rather than "created before it" |
| `ProblemTranslatorTest` | 2 | B20: the response carries the domain's code and nothing a client should render, and an un-namespaced code cannot reach a client |
| `RecordObligationApiTest` | 13 | The first write path in the product, end to end. The entry is not inspected but *verified*: the rows are rebuilt as `JournalEntry` objects - which itself enforces L1 and L6 - and handed to `EntryChain`, `Balances` and `FxLinkage`, the same verifiers an offline job runs. Covers the chain joining rather than restarting, one chain per currency, a replayed key answering with the same obligation and booking nothing, the same key in another group being another obligation, the four refusals, and cross-currency: the linked pair written to two chains, the clearing account holding both amounts, the lock's residual, a stale rate refused with nothing written, a pair with no rate at all answered `503` with nothing written, a cross-currency replay locking nothing, and the preview then reporting two currencies - the read path reconstructing the obligation from its quote |
| `ReadRunApiTest` | 8 | The projection a member approves against. The figures per currency with their transfer counts, the answers as rows, who pays whom in the stable `railRef` order (fixtures inserted in the opposite order, so an ordering by insertion would fail), the FX leg labelled from the clearing account, a failed movement's rail words, a run still being computed reading back empty rather than broken, `404` on both endpoints, the gates' verdict projected as the worker wrote it (counts, gaps per currency, the status it was reached for, and a run nobody has looked at yet reading back with no verdict at all), and the contract fields this module deliberately leaves unpopulated asserted *absent* - so that filling one is a decision rather than an accident |
| `GateConclusionTest` | 5 | What the worker stores for readers in another deployable, asserted as the columns they project: a run waiting on its group with the majority it needs and the debtor count, a run waiting on currency with one row per gap and none for what is covered, the verdict refreshed on every look (one row per run, and `concludedAt` advancing with the test clock), the stored verdict compared field by field with the verdict the worker derives from the same rows, and a conclusion that outlived its run still carrying the status it was reached for |
| `DecisionApiTest` | 8 | A member answering a run through the endpoint, either way: the row read back as the run's own columns, the word `'approve'` when the request omits one, a refusal recorded with no status written by this module, a second answer refused `409` rather than overwritten, two members each answering once, an unknown run, the clearing account's answer refused by the run's own rows, a word outside the vocabulary refused `422`, and a request with no member or a path id that is not an id |
| `OpenCycleApiTest` (API) | 9 | Opening a settlement cycle, which is the endpoint ADR 0032's mechanism needed a surface for: the row the worker reads (the four columns, with the instant being the client's rather than the clock's), a second request for the same pair being `200` with the first request's id *and* its creation instant - answering with this request's clock would describe a row that does not exist - one instant in two groups being two cycles, a request without an instant or with a malformed group id being a coded `400` that writes nothing, the two shapes of `runId` (a fresh cycle leaves it out; a cycle that already had a plan is reported with it, which is what makes a `200` truthful), and the decision asserted as an absence: a cycle opened for an instant with nothing recorded at or before it is *accepted*, because a commitment recorded tomorrow can carry an instant inside it and the refusal belongs to the worker that computes |
| `ReadGroupCyclesApiTest` (API) | 8 | The read that makes the request visible (ADR 0034): an empty list rather than an error for a group with no cycles, a row carrying the instant the books closed at and no plan, a row carrying the plan it does have, `created_at` order asserted where it *disagrees* with `as_of` (the older instant asked for later is at the top, which is the case the ordering choice was made for), one group's cycles not being another's, the limit bounded by the read rather than by the request, a coded `400` for a malformed group id, and that a left join over the worker's runs never reports one cycle twice |
| `ReadGroupMembersApiTest` (API) | 6 | The read the contract had promised since the obligations slice and the build never had (ADR 0035): an empty list rather than an error, the names and roles it serves (with the credit limit asserted *absent*, since the contract used to promise a field nothing sets), the order they joined asserted where the ids sort the other way and at the same instant, one group's members not being another's, and a coded `400` for a malformed id |
| `ReadObligationsApiTest` (API) | 10 | The read the app's main screen has always called and the API never served (ADR 0036), which is why it is the largest test class in this module: an empty list, what each commitment is and who it is between, newest first and oldest first, **the status derived three ways** (a settled run makes it `settled`, a run still working makes it `netted`, a run that gave up releases it back to `open`), the filter reading the same value the row carries, the member filter on either side, the date range including both days, a coded refusal for each of six unreadable queries, the clamp, and **a replay reporting the status the list reports** - the last one is why the store's own derivation changed |
| `ReadCurrenciesApiTest` (API) | 3 | The catalogue every amount on every screen resolves its exponent from (ADR 0036), and the quietest of the unserved reads: the exponent each currency has (JPY's zero is the one that matters), the order being by code rather than by insertion - asserted with a currency inserted that sorts before every seeded one - and the two columns nothing consumes being absent |

| `ReadReferenceRateApiTest` (API) | 6 | The last of the four unserved reads, and the test class that carries ADR 0037's business decision: an expired rate refused here **and** refused identically by the recording path (the claim is that the form is not told one thing and the submit another), the happy case asserted the other way round (**the rate served is the rate a cross-currency obligation is then locked at**), `503` as a different status from `422` because one is retryable and one is final, an unknown currency code answered with the recording path's own code, and the three malformed queries (a missing parameter and a pair of one currency) answered with a code rather than a framework error |
| `ContractCoverageTest` (platform) | 3 | The audit ADR 0035's finding asked for, run every build (ADR 0038): every promised operation is either served or recorded with its owner, no recorded gap is now served, and no handler answers an operation the contract does not declare. It reports the seventeen known gaps with the screen or decision that owns each - balances, tax, groups, activity, invites, auth, execute, void, webhooks, and one recorded as a removal candidate - and it was made to fail on all three of its own directions before it was trusted |
| `ReadGroupBalancesApiTest` (API) | 6 | The sixteenth of ADR 0038's seventeen gaps, served by the preview's own computation (ADR 0039): one position per member with the direction its sign means, the same positions the preview returns over the same rows (`agreesWithThePreviewOverTheSameOpenObligations` - the test that fails against any second composition path), a member in two currencies twice and never summed, a commitment a `done` run discharged counting for nothing, an empty array rather than an error for a group with nothing open, and a `400` problem body for a group id that is not one |
| `balances/model/rows.test.ts` (client) | 4 | The one thing this screen does that the server does not: joining positions to the group's names. A name when the catalogue has one, the identifier when it does not (the clearing account is not a person and never will be), nothing said about unnamed rows when every row has a name, and keys unique per member *per currency* so two rows for one member cannot collide |




| `DomainErrorResolverTest` | 2 | That the resolver claims domain failures and declines everything else, so a bad request keeps its own status |
| `EcbReferenceRatesTest` | 9 | The bank's own daily document - its single-quoted attributes, its GESMES envelope, and one rate quoted with five fractional digits while most have two - read as a publication: the instant is 16:00 Brussels (14:00 UTC in summer, 15:00 UTC in a January document, so a fixed offset would have dated winter publications an hour early), the table is normalised to its own widest rate (GBP at five digits pads USD to 114030 exactly), the euro's own rate is the unit, the rouble is *absent* because the bank suspended it, the most recent table wins in a document that holds several, and four refusals - not XML, no dated table, nothing at all, a document type declaration (an entity reading a local file would turn a feed outage into a disclosure), and a rate that is not a number |
| `RateIngestionTest` | 6 | One pass with no database and no network, so the assertions are about what it decided: six ordered pairs offered for the three currencies this deployment knows that the bank quotes, the store handed `libs/fx`'s derivation rather than its own arithmetic (with the derived source, the publication instant and the deployment's expiry on the row), both codes it could not use reported and distinguished - the rouble it can transact and the bank does not price (a warning) from the bank's other currencies it does not know (information), a provider with nothing to publish *not* an error, a feed that cannot be obtained a fault that writes nothing, and a repeated publication writing nothing while still reporting the sizes |
| `RateIngestionApiTest` | 5 | The claim this feature exists for, against a real database: the same commitment refused `503` before the feed is read and created after it through the HTTP API, with the derived rate, the converted amount and the residual the conversion implies; a second refresh storing nothing (so a restart or an overlap does not duplicate market data every reader then resolves by recency); the publication instant and the four-day window as stored columns; a currency the deployment transacts and the bank does not quote reported *and* its consequence refused end to end; and euro pairs appearing only after the euro is inserted into the catalogue, the ones against the publication's own base keeping the provider's name |
| `ScheduledRateIngestionTest` | 3 | The refresh's three outcomes, without a scheduler: a pass with a report, a provider with nothing to read (silent rather than failing), and a fault that is *swallowed* - because a `@Scheduled` method that throws loses its schedule, and a deployment would then read no rates until somebody restarted it. The seam the worker's `ScheduledRunDriverTest` tests, for the same reason |
| `RateFeedWiringTest` | 2 | The half the other tests cannot see, because they construct the feed themselves: a context booted with `reductxn.rates.feed` set has an `EcbReferenceRateFeed` and a `ScheduledRateIngestion`, which is what proves the conditional's property name matches the one an operator sets - and a refresh through that wiring stores the six derived rates, so the property, the adapter, the parser, `libs/fx`'s derivation and the store are exercised as one path rather than in pieces |
| `ModuleBoundaryTest` | 28 | RULEs B1-B5, B6, B8, B10-B16, B18-B22. The four that joined with the settlement worker: B6 in both directions - the first rule whose subject is *both* deployables - and the worker's own B13 and B14 |
| `money.test.ts` (client) | 8 | I4 and I5 over ~600 generated amounts in `en-IN`, `en-US`, `ru` and `ja`: an amount survives formatting and parsing, the digits of the output are exactly the digits of the integer, and the spec's own worked examples read the way the spec says they read. It found three real defects on its first run, all in the formatter and none visible in an example test |
| `i18n.test.ts` (client) | 9 | Locale matching from a browser preference list, the review-state rule as a function (an unreviewed locale is refused where money is involved), Russian's four plural categories selected at 1, 2, 5 and 21, that a fallback message is formatted with the *source* language's rules, and that a missing key falls back per key rather than emptying the screen |
| `netting-preview.spec.ts` (client, Playwright) | 8 | The preview in a real browser: per-currency amounts with no total across currencies, the currency-clearing leg named rather than hidden, the empty state, the unreviewed-locale fallback with its notice (`lang` and the text asserted together - which is how the nested-provider bug was found), the language switch as a real navigation, a visible focus ring on the skip link, no entrance animation under reduced motion, and **no axe violations including colour contrast** |
| `input.test.ts` (client) | 8 | The recording form's rules without a browser: a typed amount becoming integer minor units for the currency's own exponent, over-precise input refused rather than truncated, zero refused, the same member on both sides refused *with the server's code*, a server refusal mapped to the field that can fix it, an unreachable server as a problem rather than a crash, and whether a failure is worth retrying |
| `obligations.spec.ts` (client, Playwright) | 8 | The write path, with the requests inspected: an over-precise amount sends **nothing**, the same member on both sides sends nothing, the new row appears optimistically and is replaced by the server's row, a `503` offers a retry and **every request in the cycle carries one distinct idempotency key**, a `422` never offers one and is sent once, the indicative rate appears before a cross-currency choice, a filter is a URL, and the list shows names rather than identifiers |
| `ReadGroupRunsApiTest` (API) | 6 | The runs a group has to decide about: newest first whatever order they were written in, a tie broken by the run itself rather than by chance (needs two cycles a second apart - the schema refuses two cycles of a group at one instant), another group's runs absent, the limit honoured and clamped at both ends, an unknown group reading empty rather than 404, and a malformed id refused with a code |
| `decision.test.ts` (client) | 7 | What a run's status looks like (a tone for each of the migration's eight, and an honest "a state this screen does not know" for a ninth), a finished run recognised without claiming an answer counted, and the one refusal that means stop asking distinguished from a malformed request, a missing run and an unreachable server |
| `settlement.spec.ts` (client, Playwright) | 6 | The approval flow in a browser: the list with per-currency movements and the gates' count, an unknown status rendered as words rather than an identifier, the plan's figures and who-pays-whom by name with the group's own FX conversion labelled, an approval recorded and shown on the record without claiming it counted, a refusal recorded as a refusal, and the answer buttons disappearing after a `409 decision_already_answered` |
| `SchemaOwnershipTest` | 2 | RULE B17: no table is created by two modules, and no migration version falls outside its owner's range |
| `BookingsTest` | 10 | All three booking shapes, the direction convention asserted through `Balances`, the derived-id guarantees, the refusals, that an instant with nanoseconds books the same entry as its stored microsecond precision, and that a settlement is the mirror of the commitment it discharges - a net debit of zero for both parties, read from the ledger's own projection |
| `BookingsPropertyTest` | 2 | 300 generated rates and amounts: every booking pairs its link (asserted with `FxLinkage`) and leaves the clearing account holding exactly the two amounts; and 300 generated amounts: a commitment plus its settlement leaves both parties flat |
| `ArchUnitVisibilityTest` | 1 | That the architecture rules above are not vacuous |
| `DomainErrorCodeTest` | 1 | That every error code is well formed, namespaced by its own module, unique, and declared as a constant |
| `RuleCoverageTest` | 3 | That every RULE in the plan *or the specs* is enforced or explicitly deferred, that every claimed enforcement resolves to something that runs, and that no rule is both |
| `PropertyCoverageTest` | 3 | That every property in the specs is labelled by a test assertion or explicitly deferred, and that no test labels a property no spec defines |

All 28 architecture rules are verified non-vacuous, and that verification is automated rather than
something a reviewer has to notice. See the constraint table below.

**Coverage is checked for behaviour as well as structure.** `S1-S5` - quorum, idempotency, partial
application and the FX funding rule - are declared in `docs/specs/settlement.md` rather than in this
plan, and the first version of `RuleCoverageTest` read only this file and reported full coverage while
five rules had no decision at all. It reads the specs now.

Properties are held to the same standard by `PropertyCoverageTest`, which detects coverage rather than
declaring it: a property counts as covered when a test labels an assertion with its id
(`.as("X7: ...")`), a convention the property tests already followed. Nothing has to be maintained for a
covered property, so only the exceptions need an entry - and `N5`, ledger's `L3` and `L5`,
and all of i18n's `I1-I7` have one, with reasons. That is 10 decisions that were previously invisible,
down from 17 when the journal entry types landed and five of ledger's nine invariants gained tests,
down one more when `libs/booking` asserted L7's accounting half, and down one more when the settlement
posting made L8's end-of-cycle claim assertable - which is the shape of progress this file is meant to
record: a deferral leaves when a mechanism arrives, not when somebody decides the property is fine. The
map is checked in the other direction too: a deferral whose property a test now labels fails the build as
stale, which is how the five were found rather than remembered.

**Three guards here are not ArchUnit rules, and all three matter as much:** `forbidFloatingPointMoney`,
`forbidWildcardImports` and `forbidIgnoredSources` are Gradle verification tasks in `build-logic`, so they
run for every module rather than only for the one with architecture tests. RULE N1 had been recorded as
"not yet gated" since it was written; it is gated now. The client's counterpart lives in
`apps/web/eslint.config.js`: the same float ban, the same "no division by a hundred", and the boundary rules
that section 6.2 sketched - shipped, rather than promised.

The third guard came from a real miss rather than from a principle. A bare `out/` in `.gitignore` - the
usual Java idiom for an IDE's build directory - matched `port/out`, which section 5.4 prescribes by name,
so two source files were never committed. `git add -A` does not warn about ignored paths, the local build
was green, and only a fresh clone failed. A guard that cannot be run locally would not have been worth
adding; this one runs on every `check`.

**What is designed versus what exists.** This distinction matters more than usual after a design-heavy
change, so it is stated explicitly:

| Area | Decision | Code | Notes |
|---|---|---|---|
| Netting, tax, money | ADR 0005, 0006, 0011 | **Implemented, tested** | 21 tests green |
| Journal entries and the hash chain | ADR 0004, 0012 | **Implemented, tested, and written to** | 48 tests green. `JournalEntry` and `Posting` enforce L1, L2 and L6 on construction; the hash is derived rather than stored, so an entry whose hash disagrees with its content cannot be constructed at all; `EntryChain` and `FxLinkage` verify a chain and its FX pairing as pure functions an offline job can run. The tables exist (`libs/ledger`'s `V301`) and `POST /obligations` appends to them, so a chain that came out of a database is verified by `EntryChain` in `RecordObligationApiTest` - which is the first time that verifier has run against stored rows rather than against rows a test built. A cross-currency pair's linkage is verified the same way, by `FxLinkage` over the rows |
| FX rates and the residual | ADR 0013 | **Implemented, tested** | 49 tests green. `libs/fx` owns the rate type, the exact conversion, `FxQuote`, and since ADR 0027 the shape of a provider's publication with the cross rates derived from it |
| Rate ingestion - what fills `fx_rates` | ADR 0027; RULE B16, B17, B19 | **Implemented and tested, both halves of the feed** | 39 tests green across the two modules. `apps/api/adapter/feed` parses the ECB's real daily document into observations against the euro and reads it from a URL or a file; `libs/fx`'s `CrossRates` derives both directions of every pair among the currencies the catalogue holds at one scale; `ScheduledRateIngestion` runs it on a cadence that does not exist without a configured provider; `V403` makes a publication unique so a repeated refresh writes nothing. The end-to-end test asserts the hole this closes rather than the rows it writes: the same commitment is refused `503` before the feed is read and created after it. A currency the deployment transacts and the provider does not quote (the rouble, since 2022) is reported as a warning with its consequence tested, and no spread is applied - the mid rate is stored with the disclosure columns at zero |
| The client - one screen, end to end | ADR 0028; RULE F1-F12, C1-C3, I1-I7; P9 | **Implemented for one screen, with its gates live** | 25 tests: 17 unit and component (Vitest) and 8 browser (Playwright, with axe). `apps/web` has a toolchain, the five feature-sliced layers with the import arrow enforced by ESLint, a typed client generated from the contract, locale-aware money that never becomes a float, ICU catalogs with the review-state rule, design tokens from the `ui-ux-pro-max` query, and the netting preview wired to a real endpoint. `tools/check-catalogs.mjs` implements I1, I2, I3 (both directions) and I7. What is not there: one screen of section 6's route tree (tax), the group's invites, identity, and the execution path - and the gates that run all of it now include a pipeline (ADR 0031). Members and cycles arrived later: ADR 0035 and ADR 0034 respectively, and balances after them: ADR 0039 |
| The client's first write path - recording a commitment | ADR 0029; RULE F4, F5, F9-F12; P1, P8, P12 | **Implemented and tested** | 16 more tests (8 unit for the rules, 8 in a browser). `POST /obligations` is reachable: the amount is parsed to integer minor units by string manipulation and over-precise input is refused *in the browser* (the test asserts no request was sent), the idempotency key belongs to the attempt so a retry after a `503` repeats the same one (the test asserts every request in the cycle carries one distinct key), a `422` is never retried, the new row appears optimistically in the view being looked at and is rolled back on a refusal, refusals land beside the field that can fix them with the server's own codes, and the cross-currency choice states its indicative cost before it is made. It also found that **every server error message had been missing since ADR 0028** - see the finding about the catalog loader |
| Find a run and answer it - the approval flow, end to end | ADR 0030; RULE F4, F8; P8; RULE S1, S4 | **Implemented and tested** | The contract gained `GET /groups/{groupId}/runs` - the listing the plan's route tree has required for several slices and nothing could fill - ordered on the `settlement_runs_by_group` index `V501` had already created, with a clamped `limit` and no invented `404` or `status` filter. The client has the group's runs and one run: figures per currency, the who-pays-whom list with the group's FX clearing leg named rather than hidden, the answers on the record, and the answer form. "1 of 3 approvals so far, so 2 short" is the worker's own projection (ADR 0023); neither screen computes a quorum, and the answer is recorded rather than decided (ADR 0020). 19 tests: 6 API, 7 unit for the status vocabulary and the one refusal that stops the asking, and 6 browser |
| Per-currency composition | ADR 0012 | **Implemented, tested** | 20 tests green. `libs/planning` partitions a cycle, composes the per-currency plans, and derives the external FX requirement |
| RULEs B3, B4, B5, B10-B17, B18-B22 | ADR 0012, 0013 | **Enforced** | 28 ArchUnit rules plus `SchemaOwnershipTest`, all verified non-vacuous. B3 and B4 had been stated in section 5.1 and never enforced; B11 was described as enforced and did not exist; B12-B15 had no subject until `apps/api` had sources; B16 needed a persistence adapter and B17 a schema; B22 needed the module it constrains; and B6 - the rule the whole `libs/planning` and `libs/booking` story exists to satisfy - needed *both* deployables to have sources, which is what the settlement worker's arrival finally provided. Four rules joined with it: B6 in both directions, and the worker's B13 and B14 |
| `apps/api` preview over real persistence | ADR 0009, 0004 | **Implemented and tested** | 10 tests green against a container: the hexagonal layers, the RFC 9457 error path, the contract's preview reading `obligations` through jOOQ, and the `asOf` semantics that make a past preview reproducible |
| `POST /obligations` - recording and booking | ADR 0004, 0012; RULE B17, B15 | **Implemented and tested, both shapes** | 13 tests green against a container, 25 in the module. The first write path in the product: it records the obligation and books the entries a commitment produces, in one transaction, then verifies the stored chains with `EntryChain`, the positions with `Balances` and the FX pairing with `FxLinkage`. An ordinary commitment books one entry; a cross-currency one books a linked pair through `fx_clearing`, after locking a rate and storing the quote. Idempotent on the contract's required header - a replay answers with what was recorded and books nothing, including the quote |
| A group's members are rows, and the promise the contract had already made | ADR 0035; RULE B12, B17, B20, F4, F8; P8 | **Implemented and tested** | 24 tests (14 API, 10 client), taking the Java build from 438 to 452 and the browser suite from 24 to 28. `GET /groups/{groupId}/members` has been in the contract since the obligations slice, and the client has called it from three screens ever since - the obligations list and its filters, the recording form that cannot pick a party without it, and a run's answers - **with no implementation behind it**. So in development every screen that names a person fell back to a uuid, and no gate could see it: the client's tests stub the API, and the server had never asked for members. `V104` creates `group_members` (the pair `(group_id, user_id)` is the key, the name is what the group recorded, and the role is a word fixed by a `CHECK` that nothing consults), the read is ordered by `joined_at, user_id` through the index it was designed around and takes **no limit** (membership is bounded, unlike a group's runs), and the write exists because a read with no writer is a screen that is empty forever. It is idempotent by rule - a second request answers `200` with the row - and an add rather than an upsert, so no caller can rename anybody. `admin only` and `Member.creditLimit` are removed from the contract rather than left unimplemented: there is no identity for the first to mean anything, and nothing sets or consults the second. `Field` moved from `features/obligations/ui` to `shared/ui` for the second screen that wanted it |
| A balance is the plan's positions, as of now | ADR 0039; RULE B12, B15, B21, C1, C3; N1-N9; P5 | **Implemented and tested** | 15 tests: 6 API against a container, 4 client unit, 5 browser (including axe's contrast check). `GET /groups/{groupId}/balances` is the netting preview's own computation - the same open obligations, the same planner, the same positions - flattened to one entry per member per currency, with no `asOf` (a balance is "now") and a `flat` member kept rather than dropped. **It needs a tax rule exactly as the plan does**, because the engine assesses tax on the transfers it emits, and the contract says so rather than leaving it to be discovered. `ContractCoverageTest` now reports 30 operations, 14 served, 16 recorded, and this slice is what moved it from seventeen: serving an operation means deleting its entry in the same change. Two things are recorded rather than papered over - a cross-currency group's `fx_clearing` position is indistinguishable from a member in this schema, and the screen shows the identifier with a footnote naming both cases. The `NetPosition` DTO was hoisted out of `NettingPreviewResponse` (the contract had it top-level all along) and `MoneyDto` gained `of(Money)` |

| A contract read the client calls is a hole, and two of the three are now closed | ADR 0036; RULE B12, B16, B17, B20, B21; P1, P8 | **Implemented and tested** | 13 tests (10 list, 3 catalogue), taking the Java build from 452 to 465. `GET /v1/obligations` is the worst of the four reads ADR 0035's audit found: the obligations list is the screen the whole interface is organised around, its filters have been in the URL since it was built, and until now the only way to see an obligation this API had recorded was to record one. It answers the contract's own narrowings (status, member on either side, dates as UTC days, a two-word sort) plus a clamped `limit`, and the contract's `cursor` and its no-limit promise are removed rather than left unimplemented. **The status is derived in one Java expression rendered twice** - as the response's column and as the filter's condition - so a page cannot contain a row whose status it would not display, and the derivation reads a run's state three ways rather than two: `done` settled, `rejected`/`failed` *released* (a commitment nothing is settling is open, which is the reading the preview already used), the five in-flight states hold. `GET /v1/currencies` is the quieter one whose absence stopped money rendering anywhere: code and exponent, ordered by code, with the two columns nothing consumes deliberately absent. The replay path now derives the status the same way, so a retry cannot call a settled commitment open. `GET /fx/rates` is deferred with the decision it needs (a reference rate can be stale, and the contract's response list has no 422 for it) |
| A rate that cannot be locked is not a reference rate | ADR 0037; RULE B12, B20; P8 | **Implemented and tested** | 6 tests, taking the Java build from 465 to 471, and this closes ADR 0035's audit: all four reads the client called and nothing served are now served. `GET /v1/fx/rates` is the narrowest of them - the recording form asks for it only when the settlement currency differs from the debt currency - and the only one with a business decision in it, which is why it waited. **The decision: the reference read applies the same freshness policy as locking.** A pair whose newest rate has expired is answered `422 fx.rate_stale` rather than with a number, because the recording path will refuse that submission with the same code - so showing the number would invite a member to fill in the form and be refused later, after more work, with a number on screen that looked usable. A caveat box that says "this may be stale" cannot say "do not bother", which is the actual answer. The two failures travel as themselves (422 final, 503 retryable) so a screen can tell "not until the feed catches up" from "cannot answer yet", the response carries `quotedAt`/`expiresAt` so a number is never presented as fresher than it is, and staleness is judged in the provider and only there, which is what makes the read and the write fail identically. Also: a pair of one currency is a `400` (a fabricated identity rate is indistinguishable from a quoted one downstream), an unknown code reuses the recording path's `api.obligation_unknown_currency` (one condition, one code), and malformed parameters use the general `api.request_incomplete` three other boundaries already answer with. **The client needed no code change**, and that is evidence rather than luck: the form already rendered the rate query's failure by code, and its own comment already said the query exists to give "the only useful message the screen can give - that this pair cannot be settled in yet" |
| Every promise is either served or owned | ADR 0038; RULE C1, C3, B20; P5 | **Implemented and live** | `ContractCoverageTest` in `platform/testing`, running from the `api` CI job's `./gradlew clean build`. It is the audit ADR 0035's finding asked for, written three slices later - late enough that the four reads it exists to catch had already been closed by hand, which is recorded rather than glossed. **Three directions**, because one is how a check like this lies: a promised operation with no handler and no owner fails; a recorded entry whose handler has since landed fails (a stale entry reads as coverage, and this is what makes the list shrink); and a handler the contract does not declare fails (the client's types come from the contract, so that endpoint cannot be called at all). The seventeen remaining gaps are **reported with their owner** - balances, tax, groups, activity, invites, auth, identity, execute, void, webhooks, plus `POST /fx/quotes` recorded as a removal candidate rather than a screen - because a build that fails on work the plan already lists as unbuilt is the version of this gate that gets switched off. **It was made to fail on all three directions deliberately**, one at a time, each failure naming its own operation and nothing else; and it produced **two false reports while being written**, both now in its documentation: reading `@GetMapping(produces = ...)` as a path (the audit's own first mistake, one layer out) and capturing `("/preview")` with its parentheses, which reported three operations as un-promised handlers and one as an unowned gap from a single missing strip. Both were diagnosable only because the repository's answer was knowable by reading two files - which is the reason the parsing decisions live in the class's documentation rather than only in its code |



| `RecordedDecisionsTest` | 7 | The reading half of a crossing no module spans: a member's answer written with the API's own SQL, judged by the run's arithmetic. Two approvals settle a cycle with nothing else touching the worker; one is not a quorum; an approval from someone the run does not concern counts for nothing; an answer from the API and one from this module's use case are the same arithmetic; the run's rows refuse an answer from the clearing account; a refusal from the API leaves the run reading `awaiting_approval` until the next look writes `rejected` and moves nothing; and a stranger's refusal is recorded and inert |

| A member can see the cycles they asked for | ADR 0034; RULE B16, B17, C3, F8; P8 | **Implemented and tested** | 21 tests (10 API, 9 client unit, 2 browser), taking the Java build from 428 to 438 and the browser suite from 22 to 24. `GET /v1/groups/{groupId}/cycles` is one page of cycles, **most recently asked for first** - `created_at`, not `as_of`, because a member who has just asked for last quarter's settlement wants it at the top - backed by `V511`'s `settlement_cycles_by_group`, the index the contract names. Each row carries `runId`, which is a *fact* rather than a status: present means the plan exists and is readable at `/runs/{runId}`, absent means there is none - and that absence is deliberately ambiguous, because a cycle asked for a moment ago and one the worker will never plan (nothing recorded at or before the instant) would only be told apart by *recording* the refusal, which is a column and a judgement this module does not make. The open response resolves the plan too, which costs the write one left join and makes its `200` truthful. On the client: a card that closes the books at an instant and lists what became of each cycle, polling while any is waiting and stopping when none is; the wall-clock conversion moved to `shared/lib/localTime` when a second feature needed it; and nothing in the form pre-judges an empty cycle - a check that is *stronger* to refuse in a browser, since a backdated commitment is the ordinary way to settle late |

| A member can ask for a settlement, not just answer one | ADR 0033; RULE B6, B16, B17, B20, B21; P8 | **Implemented and tested** | 7 tests. `POST /v1/groups/{groupId}/cycles` writes `settlement_cycles` through `JooqCycles`, the second crossing into the worker's rows and the smaller one: four columns, an id the API mints, and the pair `(group_id, as_of)` arbitrating which id survives - `JooqRuns.open` inserts its own proposal with `on conflict do nothing` and reads back the row the pair has, which is why no id derivation crosses the boundary. Opening is idempotent (`201` when this request opened it, `200` when it was already open, both carrying the cycle), the instant is required so a double click cannot settle a group twice, and **the operation refuses nothing**: a group's commitments are this module's rows, so "nothing to settle" is answerable here, and answering it here would refuse a request the worker satisfies a minute later, because a commitment recorded tomorrow can carry an instant inside this cycle. `close` and `POST /cycles/{cycleId}/runs` are removed from the contract rather than left unimplemented - nothing can store a closed cycle, and a cadence computing an opened cycle left the second operation nothing to ask for. The client's generated types are regenerated in the same change (RULE C3) |

| Reading a run - the other half of consent | ADR 0021, 0023; RULE B6, B17, B20 | **Implemented for the rows and for the gates' verdict** | 11 tests green. `GET /v1/runs/{runId}` and `/transfers` project this worker's run, partition, transfer and decision rows: the figures per currency, who pays whom in the table's own stable order (`rail_ref`, which V501's comment promises), the FX legs labelled from `libs/planning`'s model of the clearing account, each movement's state and what the rail said, and every answer on the record. Nothing is recomputed: quorum, the external requirement and reporting totals are this worker's judgements, and a second implementation in the API would be the copy a member reads while the first moves money. The contract's `reportingTotals`, `fxRequirements` and `executedAt` are documented as unpopulated with their reasons, `TransferInstruction.tax` is no longer required (tax is per currency, not per movement), and the tests assert the absences so filling one is a decision rather than an accident. Since ADR 0023 the run also carries `gates`, the verdict the worker writes down when it reads its own gates - projected here, never recomputed, and absent until the worker has looked at the run
| A cycle can be opened before it is computed, and something computes it | ADR 0032; RULE S1, S4; P8 | **Implemented and tested** | 21 new tests in the worker (four classes and five command cases), taking the build from 400 to 421. A cycle and the plan over it were one act, so nothing could be opened ahead of being computed and there was nothing for a scheduler to find: `OpenCycle` writes the cycle alone (idempotent on `(group, as_of)`, the id derived in one place so an opened cycle and a computed one are the same row), `ComputeOpenedCycles` computes the cycles that have no run - one transaction per cycle, a refusal reported and a fault contained - and `ScheduledRunComputer` is the cadence, with `--reductxn.command=open` and `compute-opened` for a deployment that runs this worker as a job. The tests also found the invariant this breaks: `JooqRuns.describe` treated a cycle with no run as a lost row, and it is now an ordinary state. What is deliberately *not* decided is when a cycle is due - that is a group's schedule, still open, and the reason `open` has no cadence |

| The append path and the chain-head adapter | ADR 0004, 0012 | **Implemented and tested** | `JooqChainHeads` takes a Postgres advisory lock per `(group, currency)` before reading the chain's head, and refuses to be asked outside a transaction, because a lock whose lifetime is the transaction cannot protect a read that is not in one. The head is the chain's *leaf* rather than its newest entry by time: `occurred_at` is when a commitment was made, so recording one with a past instant would otherwise be mistaken for the head. `journal_entries_one_successor_per_hash` is the second line of defence, making a forked chain unrepresentable |
| A member being told why a run is waiting | ADR 0021, 0023; RULE S1, S4, B17, B21 | **Implemented** | 8 tests green across two modules. "Why is this waiting?" is quorum's arithmetic and the funding requirement, neither of which is a row - so `apps/api` could report the answers and the status and nothing else, and a member had to combine them. `ExecuteRunService` now writes what the gates concluded (`GateConclusion.of`, which reads `Quorum` and `Funding` rather than deciding anything) into `settlement_run_gates` on every look, and the read endpoint projects it: quorum met, approvals counted and required, how many debtors still have to confirm, the funding gaps per currency, the status the verdict was reached for (a conclusion outlives its run) and when it was read (unconditional, so `concluded_at` means "last looked"). The worker's `GateConclusionTest` asserts the columns and that the stored verdict agrees with the one it derives; the API's tests write the same rows by SQL and assert the projection - including that a run nobody has looked at yet has no verdict rather than an invented one. The rail-support gate is deliberately absent (it is the rail's declaration, not a run fact), and the verdict carries a debtor *count* rather than a list, because the member set is `net_positions`' |
| A rail that has gone quiet | ADR 0019, 0024, 0025, 0026; RULE S3, S6, B17, B21 | **Implemented as a report, answered by a person, with both reasons** | 11 tests green across three classes. Section 4.4's "or escalate" was the last open half of that section: a transfer no rail answers is retried with a backoff and then polled for ever, and nothing said when patience had run out. `reductxn.settlement.escalation.after` (two hours by default) is that number, `V508` stores when a spell of silence began so the duration is a fact rather than an inference from a retry count, and `Escalation` turns the policy into the cut-off a query compares against - the driver's window's arrangement. The cadence logs a warning per silent transfer (state the rail left it in, attempts, the rail's own words, and how long) and `--reductxn.command=escalations` prints the same listing on demand with a limit that says when it truncated. Nothing is written about having said it (a silence that has not ended is still happening), and nothing is written about the transfer: the absence of an answer is not evidence, so `failed` would assert the money did not move and `confirmed` would assert that it did. The test for the failed-run case turned up a live hole - a `failed` run was not covered by the guard that stops a finished run being driven again - which is closed, with the evidence recorded. ADR 0025 closes the gap this row named - a person who has the answer can record it - and the row below is that work |
| A transfer no rail will answer, resolved by the person who asked | ADR 0025; RULE S2, S3, S6, B15, B17 | **Implemented** | 17 tests green across two classes. ADR 0024's open question - who may assert that money moved - is answered with "a person may, and the record says who and on what basis": `V509` stores `resolved_by`, `resolved_basis` and `resolved_at` beside the state, the five options of `--reductxn.command=resolve` are required, and `TransferResolution` refuses an attribution in pieces, so an unattributed claim about money cannot be built at all. Nothing verifies the name (there are no identities in this build), which the ADR states rather than glosses: it is an attribution, not an authentication. The write is bounded by design - **no rail call** (the operator already has the answer; asking again would make the command depend on the very silence it exists for), **no other transfer touched** (an operator resolving one movement has not asked for the run to be attempted), and **the confirmation path or nothing**: a `confirmed` answer appends the same `Bookings.settlement` entry a rail's confirmation appends, derived from the transfer, in the same transaction as the state write, while a `failed` answer books nothing because a posting there would record a movement nobody made. The silence is cleared, so the escalation report stops naming the transfer it came from, and the run's fate is written by `RunOutcome` - extracted from `ExecuteRunService` so that the two writers of a run's status cannot disagree about it. Three refusals, each a case where the assertion cannot be true of the row: an answer already stored (and the refusal names who decided, when and on what basis, which is exactly what an operator re-running after a slow response needs to see), a transfer no rail was ever asked about (`pending` - there is no answer to have found out), and a transfer that is not in the run named. What is deliberately *not* required is that the transfer be past `escalation.after`: patience is the worker's prompt to a human, not a permission a human needs |
| A rail that has the money and will not confirm it | ADR 0026; RULE S2, S3, B17 | **Implemented** | 6 tests green across three classes. Section 4.4's fourth requirement, and the hole ADR 0025 found while writing a test for its own resolution: a rail that accepts an instruction and then keeps answering "still pending" leaves no silence, resets the retry counter on every poll, and could sit on a movement for a week while every measure this worker had said the transfer was healthy. `V510` stores `submitted_at` - written with `coalesce`, so a later poll cannot restart the wait - and `reductxn.settlement.reconcile.after` (one hour by default, deliberately shorter than the two-hour escalation default, because a rail holding the group's money is a different thing from a rail that has stopped answering) is the second policy. The report a person reads now carries both reasons, one line each with the age measured from where the trouble began, because an operator should not have to remember to run two reports; `StuckTransfer` replaced `SilentTransfer` for the reason the repository keeps finding - a name and an invariant that had stopped being true - and `JooqRuns.stuck` is one predicate shared by the listing and the count, so the two numbers an operator decides with cannot drift. What it produces is attention, not an action: failing a stale submission would assert that money did not move, and stopping the polls would abandon the only cheap way to learn what became of it |
| `currencies`, `fx_rates`, `fx_quotes` | ADR 0013; RULE B17 | **Implemented, and read by the write path** | `libs/fx`'s `V401` creates `currencies` and seeds three rows, because the contract's `Money` carries a currency code and no exponent: nothing can be recorded until the exponent can be resolved. `V402` creates `fx_rates` and `fx_quotes`, the provider adapter reads the first and the record path writes the second. Nothing ingests rates yet, so every cross-currency request fails closed until something does - which is the designed behaviour, not a gap |
| `platform/persistence` | - | **Implemented and tested** | 2 tests green. jOOQ and Flyway wiring, PgBouncer-compatible JDBC settings, and a guard that refuses to start when Flyway finds no migrations |
| `libs/booking` | ADR 0012, 0013, 0017; L7, L9; RULE B6, B22 | **Implemented, tested, and called by both deployables** | 12 tests green, two of them 300-case properties. All three shapes book correctly - an obligation in one currency, one across two, and the settlement of a movement - L9 is asserted with the verifier that will run against the stored entries, and L7's accounting half moved out of the deferral map. It is an abstraction with two callers rather than one: `POST /obligations` books a commitment, and a run books what its rail confirmed, so the decision this module contains is the decision the ledger records |
| `fx_clearing` postings | ADR 0012 | **Implemented** | A cross-currency commitment books the linked pair: the debtor's side in the debt currency and the creditor's side in the settlement currency, with the clearing account as counterparty of each. The tests read the rows back and assert the clearing account holds the owed amount in one currency and owes the converted amount in the other - which is L8's measurement, and the only statement the ledger can make about a pair without a rate (RULE B19) |
| `libs/rails` | ADR 0001, 0019; RULE B4, S2 | **Implemented: one adapter and a breaker, tested** | 13 tests green. The port, `MockRail` (reached only through `Rails.mock()`, RULE B10), and `Rails.withCircuitBreaker` decorating any rail. A refusal is classified by the port itself - `RailUnreachable` (nothing offered, nothing moved, send it again) or `RailRejected` (the rail said no, and asking again changes nothing) - which replaced an inference in the worker: every `Result.fail` used to be read as retryable, which is right for the mock and wrong for a 400. The mock confirms what it accepts, settles the three currencies the catalogue seeds, refuses anything else terminally, and answers a re-submission with the state it already holds. The breaker opens after N consecutive *unanswered* calls, allows one probe per cooldown, closes on any answer, and its counters are in memory because a restart is what lets an operator recover a deployment |
| The run: quorum, funding, execution, the ledger, and what drives it | ADR 0012, 0016, 0017, 0018; RULE S1-S4 | **Implemented, executed, booked, and driven** | `workers/settlement` has sources, a schema (`V501`-`V505`, eight tables) and 74 tests against a container. It opens a run per `(group, as-of)`, computes the plan through `libs/planning`, records it *and the commitments it nets*, gates it on quorum (S1: a majority of the run's own participants, and every net debtor personally) and on funding (S4: the exact external requirement per currency, recorded as it arrives), and submits the transfers to a rail - confirming them, or failing the run partially with the rail's own reason when something is refused (S3). Every confirmed transfer is then booked as a `settlement` entry, so a settled cycle leaves every member flat and `fx_clearing` at zero per currency (L8), and a commitment a settled run discharged is not open again - the double settlement that would otherwise move the group's money twice for one debt. All eight run states are reachable, and a cadence plus an operator's command line now *drive* them: before that, the only caller of `ExecuteRun` in the repository was a test, and the worker - a non-web application with no non-daemon thread - exited at startup instead of running as a service. What is still missing: nothing decides when a *cycle* is due (group schedules do not exist), a member's approval has no channel to the worker, nothing escalates a rail that never answers, retryable failures are retried by the next pass rather than with backoff, and **nothing is posted for the currency a group brings in from outside** - that entry needs a group account, and the ledger's account typing is an open question |
| Localization | ADR 0014 | **Not implemented** | No catalogs, no `shared/i18n`, no locale resolution |
| RULEs F10-F12 | ADR 0014 | **Not enforced** | Listed in section 8 as gates, not yet written. B20 was in this row and is enforced now |

So the honest position has moved once more, and this time in the direction of *operational* honesty rather than
correctness or existence. A rail that did not answer used to be retried by whichever caller happened to re-run the
pass, and a rail that answered "no" was retried too - because the port's contract could not say which was which and
the worker inferred it from the shape of the result. Now the port classifies (`rails.unreachable` versus
`rails.rejected`, in the type, with stable codes), the wait between attempts doubles per transfer and resets on any
answer, and a rail that keeps failing is left alone by a circuit breaker in front of it. A refusal that will not
change fails the transfer and the run (S3) instead of being offered again for ever, and *"the rail is quiet"* is a
state an operator can read off a row rather than a log line they have to catch.

The books agree with the money (ADR 0017), something makes the money move (ADR 0018), the moving behaves when the
rail does not (ADR 0019), and **a group's own members can now set it in motion (ADR 0020)**: an approval is
recorded by `apps/api` into the run's decision rows and judged by the run's quorum, so a cycle goes from
`awaiting_approval` to `done` without anyone touching the worker. A member can read the run they are answering, and
answer it either way. And **a member can be told why a run is still waiting (ADR 0023)**: what the gates concluded -
quorum met with the approvals counted and required and how many debtors still have to confirm, and the currency
still outstanding per currency - is written down by the worker when it reads its gates and projected by the read
endpoint, so "you are one approval short" is sayable without the API implementing the settlement rules. Consent is
complete: a group can approve a plan, refuse it, see what happened, and see what is still missing.

What is left of that path is *who* - the verdict carries a count of the members still to confirm rather than naming
them, because the run's members are its net positions and this read model does not publish them. Publishing the
participants is the honest fix, and it is a read model change rather than an arithmetic one.

A settled cycle leaves every member flat and `fx_clearing` at zero per currency (L8), the commitments the run
netted closed rather than open, and a run that could not be settled says so instead of retrying until somebody
notices.

What is deliberately narrower than "settlement works":

- **Nothing decides when a cycle is due.** A run is driven once it exists, and an operator can open one by
  command - but nothing says "this group's cycle has come round", because a settlement schedule is a group's
  property and `groups` does not exist. Until that lands, a deployment's cycles are opened by a person or not at
  all, and the alternative ("settle whatever is open, every tick") would decide about a group's money on the
  group's behalf.
- **A member's approval cannot reach the worker.** This is the largest remaining gap in the product: a run reaches
  `executing` only when `DecideOnRun` is called, and the only caller is a test. It needs identity (who is a
  member), a member-facing surface (the API) and a channel from there to this module - and the operator's command
  deliberately does not approve on a member's behalf, which its end-to-end test makes visible by recording
  approvals through the port.
- **A rail that never answers is now reported, and that is as far as the worker goes.** The driver resolves what a
  rail has decided, and since ADR 0024 it also reports the transfers whose silence has outlasted patience: a warning
  line per pass (and `--reductxn.command=escalations`) naming the transfer, the state the rail left it in, how many
  calls went unanswered and the rail's own last words. What it does *not* do is resolve them - the absence of an
  answer is not evidence of anything except silence, so marking a quiet transfer failed would assert the money did
  not move and marking it confirmed would assert that it did. Section 4.4's "or escalate" is closed as far as a
  worker can close it; the resolution is a command for an operator who has asked the rail, and it is the next step.
- **The currency a group brings in from outside is not posted.** The funding is recorded as a fact about the run
  (`settlement_fx_fundings`) and moves no entry, because the entry that would show the group's own contribution
  needs an account for the group - and whether `fx_clearing`, a group account and the tax and fee accounts are
  group-scoped or global is an open question in the ledger spec rather than a detail to guess at.
- **Escalation is implemented, and it stops at saying so - and then a person may answer it (ADR 0024, 0025,
  0026).**
  Patience is one number (`reductxn.settlement.escalation.after`, two hours by default), the start of a spell of
  silence is a stored fact (`V508`), and the report is a warning line on each pass plus
  `--reductxn.command=escalations`. Nothing is written about having said it - a silence that has not ended is still
  happening, and a "we told them" column would be a memory of a log line - and nothing is written about the transfer
  itself, which is the point: silence is not evidence. **And silence is not the only way a transfer gets stuck
  (ADR 0026):** a rail that takes the instruction and keeps answering "still pending" leaves no silence at all, so
  `V510` stores the first acceptance, `reductxn.settlement.reconcile.after` (one hour) is the second policy, and the
  same report carries both reasons - one listing, because the person on call has one question. What closes the loop
  is not the worker but a person: `--reductxn.command=resolve` records what somebody found out, with `V509`'s
  `resolved_by`, `resolved_basis` and `resolved_at` beside the state, books the movement a confirmation books when
  the answer is that it moved (and nothing when it is not), clears the silence so the report stops naming it, and
  lets `RunOutcome` decide the run's fate. Nothing verifies the name, so it is an attribution rather than an
  authentication - the remaining gap is identity, not the write. One hole was found and closed on the way: a
  *failed* run could be resumed by a manual pass, because the guard against driving a finished run covered `done`
  only.
- **A rail that answers "no" is now reported rather than retried.** A refusal the port classifies as terminal
  fails the transfer and the run (S3), and everything else - a rail that is silent, throttling or unreachable -
  waits out an exponential backoff and is eventually left alone by a circuit breaker in front of the rail (ADR
  0019). What has *not* changed is what a caller does with a transfer the rail accepted and never confirmed: it
  is polled, and the poll never gives up.
- **A rate now arrives, and the cross-currency write path works in a deployment (ADR 0027).** This replaces the
  sentence that used to read "no rate ingestion, unchanged: the tables exist, nothing writes a rate, and every
  cross-currency request fails closed". `reductxn.rates.feed` names a provider's document - the European Central
  Bank's daily reference rates, by URL or by file - and `ScheduledRateIngestion` reads it into `fx_rates` on a
  cadence, deriving both directions of every pair among the currencies the catalogue holds (`CrossRates`, in
  `libs/fx`, because a ratio of two observations is rate arithmetic) and storing them idempotently (`V403` plus
  `ON CONFLICT DO NOTHING`). Freshness is the deployment's (`reductxn.rates.max-age`, four days by default, because
  a daily provider publishes nothing at the weekend), no spread is applied, and a deployment with no feed still
  refuses every cross-currency request loudly. What is still open: a *failover* provider, which is a policy for
  choosing between two sources rather than a second URL; and the spec's rate bound, which the derivation enforces
  and neither the type nor a CHECK constraint does.
- **Localization is still Phase 1 scaffolding**, before the first screen exists to retrofit.

The order that follows from that: a scheduler or an operator's command, because nothing settles without one - done
(ADR 0018, 0019); then rate ingestion, which is what a deployment actually needs before a group can record a
cross-currency commitment at all - done (ADR 0027); then the client, which has no sources yet.


Toolchain constraints discovered by actually running the build. All are now pinned with reasons:

| Constraint | Why |
|---|---|
| **Gradle >= 9.1** | Java 25 support for *running Gradle* begins at 9.1. Gradle 8.14.x tops out at Java 24. This project uses 9.7.1. |
| **ArchUnit >= 1.5.0** | ArchUnit 1.x before 1.5.0 cannot read Java 25 bytecode (class file major version 69). It imports zero classes, so every rule fails as `emptyShould` - or, if `failOnEmptyShould` is ever disabled, **passes silently while enforcing nothing**. Verified by importing 30 classes explicitly. |
| **No `includeEngines(...)` on Test tasks** | Filtering by engine name silently excluded the ArchUnit engine (id `archunit`), so the architecture rules did not run at all and the task reported "did not discover any tests". |
| **ArchUnit package identifiers are not regex** | `(kernel|ledger|...)` matches nothing. Use `resideInAnyPackage(...)`. |
| **Every module a rule mentions must be on `platform/testing`'s classpath** | Otherwise ArchUnit imports none of its classes and the rule checks nothing. This is the *silent* form of the bug above: the subject set stays non-empty, so `emptyShould` never fires, while the `should` clause names a package ArchUnit has never seen. It produced a completely bogus green result for `b19` - a rule asserting no module depends on FX, while FX was invisible. Now caught by `ArchUnitVisibilityTest`, which was itself verified by making it fail on purpose. |
| **A rule can only forbid what it can see** | `b21` lists `apps/api` and `workers/settlement` as forbidden dependencies, but those have no sources, so ArchUnit has no classes to match and a violation would pass unnoticed. The rule is not vacuous - its subject resolves and is evaluated - but two of its exclusions are inert until the deployables exist. Stated in the rule itself so nobody reads a green tick as more than it is. |
| **A dependency rule cannot see an inlined constant** | `javac` substitutes a `public static final` compile-time constant at the use site, so a reference to one from another module leaves no trace in the bytecode - and a dependency rule reads bytecode. Found by activating `b10` for `libs/ledger`: a field reference passed the rule while a call to a method on the same class failed it. Not a fault in the rule, since a copied constant creates no coupling, but B10 cannot keep outsiders out of a constant, so `EntryHash.hexLength()` is a method rather than a constant. `PowersOfTen.MAX_EXPONENT` is the remaining instance, and it is referenced only inside its own file. |
| **A rule can only name types it can see, too** | The same lesson one level down from the row above, and it bit again: `platform/testing` did not depend on Spring, so `b15` could not name `@Transactional` and `b20` could not name `MessageSource`. A rule that cannot see the type it forbids is a rule that checks nothing. Spring is a test dependency there for exactly this reason. |
| **Spring Boot 4 is Jackson 3** | The databind package is `tools.jackson.databind`, not `com.fasterxml.jackson.databind`; only the annotations stayed at `com.fasterxml.jackson.annotation`. Found by compiling rather than by reading release notes, which is the cheaper of the two ways. |
| **Spring Boot 4 manages JUnit 6, and the catalogue pinned JUnit 5** | `spring-boot-starter-test` brings platform 6 while the pure modules pinned 5.11.4, so the launcher resolved to 6.0.3 against an engine at 1.11.4 and the engine refused to discover any tests: `OutputDirectoryCreator not available`. Not a version preference - two platform majors on one classpath is a broken test run. The repository is on one stack now, at the versions Boot manages, which jqwik 1.9.3 accepts. |
| **`@ExceptionHandler` cannot target an interface** | It requires a `Class<? extends Throwable>`, so "handle every exception that implements `DomainError`" - which is precisely what "every domain failure carries a code" means - is not expressible as a controller advice. `apps/api` uses a `HandlerExceptionResolver` instead, which has a second advantage: it can decline, so a framework error keeps the status it deserves rather than becoming a 500. |
| **Spring Boot 4 no longer ships `@AutoConfigureMockMvc`** | No artifact in the module exposes it. MockMvc is built from the framework directly (`MockMvcBuilders.webAppContextSetup`), which is one less Boot test dependency for a module that needs none of the rest of them. |
| **A green local build says nothing about the commit** | A bare `out/` in `.gitignore` matched `port/out`, which section 5.4 prescribes by name. Two source files were never committed: they existed locally, so everything compiled and every test passed, and a fresh clone failed to compile. `git add -A` does not warn about ignored paths. The signal is now `forbidIgnoredSources` on every `check`, and the lesson generalises - the artefact a green build proves nothing about was the commit itself. |
| **Flyway has one version sequence per schema, not per location** | So "migrations live beside their owning module" and "versions are a single sequence" are incompatible, and the first two migrations in this repository collided: "Found more than one migration with version 1" and the application refused to start. The fix is a version range per owner (`1xx` for `apps/api`, `2xx` for `libs/netting`), checked by `SchemaOwnershipTest`. Worth recording because both rules were already written down and nobody had multiplied them together. |
| **Setting `spring.flyway.url` stops Flyway inheriting the DataSource's credentials** | It does not fall back to `spring.datasource.username` and `password`, so it tried to connect with none and the server answered "SCRAM-based authentication, but no password was provided". Setting one property to point migrations away from the pooler silently detached them from three others. |
| **A `Location[]` concatenated into a message is an object identity** | The guard's refusal message ended "...Flyway read from `[Lorg.flywaydb.core.api.Location;@797fcf9`". The message exists to say *where* Flyway looked, and that was the one thing it no longer said. Caught by an integration test asserting the message names the location it was pointed at, which is a reason to assert on messages and not only on types. |
| **Gradle fails a build whose task input could contain another task's output** | `fileTree(root){ include("**/src/main/resources/db/migration/*.sql") }` resolves the whole repository - including every `build` directory - and Gradle reported an implicit dependency on `:apps:api:test`. Fair: an input that claims to look everywhere cannot be ordered against. Module-anchored patterns, as the neighbouring input already used, keep the same files. |
| **A sabotage harness that searches for the wrong string certifies the rule it failed to break** | The first run of the B16/B17 sabotage reported all three rules inert. ArchUnit *rules* are fields and report as `name FAILED`, while JUnit *methods* carry `name() FAILED` - the harness looked for the field form and never matched a method. The rules were working: an unforced run failed correctly, and `--rerun-tasks` proved it. The harness now accepts both, because a check that cannot see its own failure is worse than none - it signs off on the thing it was supposed to question. |
| **A new module's rule fails as vacuous, and only `emptyShould` catches it before the visibility guard does - twice now** | RULE B22 names `io.reductxn.booking..`, and `platform/testing` did not depend on `libs/booking`, so ArchUnit imported none of its classes: the rule reported "failed to check any classes". `ArchUnitVisibilityTest` exists for exactly this and did not catch it, because its list of modules is hand-maintained too - the list had no entry for the new module either. What saved it was `failOnEmptyShould`, which is on. Two hand-maintained lists, and the guard that watches the first is not watched by anything; that is worth knowing rather than fixing with a third list. **The prediction came true when `workers/settlement` gained sources**: B6 and the worker's layer rules failed the same way for the same reason, and `ArchUnitVisibilityTest` again did not catch it because `settlement` was not in its list either - the guard against blind rules was itself blind to the module. The visibility guard is now watched by something, in the sense that a new module's *absence* fails the build rather than passing quietly. `platform` was added at the same time, for a related reason: two rules name `io.reductxn.platform..` as an exclusion and there were no platform classes imported, so that half of them had never been evaluated. |
| **jqwik's `Combinators.combine` returns a combinator, not an arbitrary** | So `filter` and `map` do not exist on it - only `as` and `flatAs` - and the compile error names an inference variable rather than the method that is missing. The generator has to materialise its terms before filtering. The same file also found that `Arbitraries.nothing()` carries a Void that must be disambiguated at every call site, which is why the filter version reads better. |
| **Two coverage maps, and a rule can be decided by the wrong one** | A new MASTER-PLAN row named booking's coverage with the literal word RULE in front of "L7, L9", and RuleCoverageTest then demanded a decision for L7 in *its* map - which is for B/S/R/P/N rules. The L-properties are declared in `docs/specs/ledger.md` and decided in PropertyCoverageTest, which had just moved L7 out of its deferral because a test labels it. The two maps disagreed about who owned L7, and wording caused it: a prefix that reads as a rule declaration is one. Widening the reference format to cite a guard in another module was tried first and abandoned - a Gradle dependency exposes main classes, not another module's tests, so the cite could not resolve - and the abandoned mechanism was reverted rather than left in place unused. This paragraph was then rewritten to describe the offending text instead of quoting it, because quoting it produced a fresh declaration and the same failure: the checker cannot tell a mention from a claim, which is a limitation worth knowing before writing prose about it. |
| **A digest can cover a value the database cannot keep** | `Commitment` took an `Instant` and `JournalEntry` hashed it; Postgres keeps microseconds in a `timestamptz` and Java's `Instant` has nanoseconds. So an entry built from `Instant.now()` stored a row that rehashed to a *different* value - meaning the chain an offline verifier recomputes from the rows would not match the hashes in them, and the ledger's central claim, that it can be verified from its own rows, would have been false. Nothing in the pure modules could have caught it: the model was internally consistent and only the round trip through storage disagreed. Found because `RecordObligationApiTest` walks the stored chain instead of inspecting it, and fixed where entries are built - `Commitment` truncates to microseconds, so the hash is a function of what can be stored. |
| **A hash that covers an order has to store that order** | `EntryHash` deliberately covers postings *in the order they are held*, so that a reordering a reader would see is detectable rather than normalised away - which is right, and means an entry can only be verified from its rows if the order it was written in can be read back. The first draft of `postings` derived the primary key from a posting's position and stored no position, and a UUID does not sort in position order: `ORDER BY id` returned the two postings shuffled, so the rebuilt entry rehashed differently. The column is now `position`, unique per entry - a near-miss that only appeared once a chain was verified from storage. |
| **A fail-closed path needs a status the contract may not have** | The contract promised `settleInCurrency` would "fail closed if no fresh rate is available (code `fx.rate_stale`)" and listed `422` as the only failure. `fx.rate_unavailable` is not a 422: the request is answerable, the server just cannot answer it yet, and a client that retried a 422 forever or gave up on a 503 would be wrong in opposite directions. So the contract was edited first (RULE C1) to add a `503` with the two codes distinguished, and the code enforces the distinction in one map. `HttpStatus.UNPROCESSABLE_ENTITY` also turned out to be deprecated in Spring 7 in favour of RFC 9110's `UNPROCESSABLE_CONTENT` - the same 422 on the wire. |
| **Two written rules disagreed about what a code is namespaced by** | The contract says a code's first segment names "the owning domain module" and its examples are `ledger.*`, `fx.*`, `settlement.*`, which reads like `obligation.*` for an obligation. `DomainErrorCodeTest` derives the namespace from the package's module segment instead, and every class in `apps/api` sits in `io.reductxn.api..`. The rules agree for the libraries and diverge for the one deployable that owns several domains, so the codes are `api.obligation_*` - the mechanical rule won because it is the one that runs, and the divergence is recorded where the constants are. The same guard then reshaped the design: it requires one `public static final String CODE` per `DomainError`, so a single record holding four codes - the first shape - could not be enumerated for the parity gate, and became one type per code. |
| **A read model is per reader, and that is a real cost** | The worker needs the group's open obligations and the tax rule in force, and it may not call `apps/api` (RULE B6), so it reads both tables with its own projection - including a copy of the tax-rule read model, of which about a hundred and fifty lines are the rule-shape mapping rather than the query. Stated rather than glossed: this is what a read model *is*, the two are projections of one table rather than two models of one concept, and the guards that matter are repeated deliberately so that drift shows up as both of them throwing on the same input. If a third reader appears, that mapping is the thing to extract into a module of its own - which section 12 says needs an ADR, which is why it was copied today rather than invented. |
| **The natural transaction boundary is the wrong one, and only for the one use case that talks to the outside world** | Every use case in this repository is `@Transactional` and should be. `ExecuteRunService` must not be: the rail is outside the database, so one transaction would hold a connection open across every submission, and - the part that matters - a rollback after a transfer confirmed would erase the only record that money moved. The fix is a `TransactionTemplate` around each individual write, which is the same thing the spec asks for in a different vocabulary ("every state transition is persisted before the next call is made"). What makes this worth recording is that the exception is invisible in review: the annotated version is shorter, reads more consistently, and is correct until the first partial failure. |
| **A doc and its code can disagree while both read as correct** | `FundingNotRequired` was documented as covering "needs it and has it" - a replay of a completed funding - and the code refused only currencies the plan never required, recording an over-funding instead. The docs were considered rather than sloppy (both were written to be read), and the disagreement was only visible in the failure of a test that asserted the documented behaviour. The resolution was to keep the code and fix the doc, because recording money that arrived is the more honest behaviour; the retry case is handled by the reference being the funding's identity. A second instance in the same slice: a comment claimed `Quorum`'s sets were sorted "so the report is stable", and they were built with `Set.copyOf`, which has no iteration order at all. Two claims written before the mechanism, in one slice, is the pattern rather than the incident. |
| **A measure that resets on every answer cannot see a slow yes** | The retry counter is the right measure for a rail that is not answering, and it is structurally blind to a rail that answers without progressing: every poll that said "still pending" was read as evidence of health, so a transfer at the rail for a week looked like one that had just been submitted. The generalisable tell is worth keeping: when a metric is *reset* by any reply, it measures the existence of replies rather than their content - and the pathological case, in which the counterparty answers promptly and does nothing, is reported as normal. The fix is a measure anchored to an event rather than to the latest contact (`V510`'s acceptance instant, kept across polls with `coalesce`), which is the same shape `V508` had already chosen for silence. |
| **An instant is a fact about an event, so a migration with none must say so** | `V510` leaves `submitted_at` null on rows that were already at the rail when the column arrived, and those rows are stamped the next time the worker polls them. The alternative - backfilling from `created_at` - is what a migration usually does and it is wrong here: `created_at` is when the group's cycle was planned, and the column exists only to be measured against a threshold, so filling it would have produced a number that is precise, plausible and unrelated to the question. A null that self-heals in one pass is better than an instant nobody can justify, and the same reasoning is why no CHECK ties `submitted` to a non-null instant: the only row that could not satisfy it is the one the constraint would be written for. |
| **A name that has stopped being true is worse than churn** | `SilentTransfer` became `StuckTransfer` across six classes, four test classes and four documents - all mechanical, half an hour - and the alternative was a type called silent carrying transfers with no silence, whose constructor invariant ("a resolved transfer has been decided by a rail that answered") was quietly wrong for the new case. The rename was cheap *because* the vocabulary is one word in a handful of places, which is itself the signal: a concept that is named consistently is cheap to rename, and one that is not is expensive to reason about. The rule this repeats: the failure mode the repository keeps finding is not a wrong decision but a document and its code disagreeing while both read as correct. |
| **A ratio of two rates is only exact at one scale** | A provider quotes some currencies with two fractional digits and some with five, and the pair a deployment needs is a ratio of two of those observations - which is only exact if both are expressed at the same scale. So the whole table is normalised to its own widest rate at parse time, and the property that makes that safe is not "close enough": the scale appears in neither the numerator nor the denominator, so multiplying both observations by ten and raising the scale by one must produce the *identical* derived rate. That is a property test rather than a comment, because the alternative - a per-currency scale and a division that assumes otherwise - is a bug that shows up as a rate that is right to three digits. |
| **A scheduled job has no memory, so idempotence belongs in the schema** | The loader runs again after a restart, a second instance may overlap it, and an operator can repeat it by hand. Remembering what it already wrote would be a second record of what `fx_rates` already records, and that memory is exactly what a restarted job does not have. `V403` makes a publication unique on `(pair, source, instant)` and the writer inserts with `ON CONFLICT DO NOTHING`, returning how many rows the database accepted - so "the provider published a new day" and "we have read this one already" are the same code path with different counts. The general rule: when a job's correctness depends on not repeating work, put the memory in the table, because the table is the one thing that survives the restart. |
| **A provider's absence is a fact the deployment has to act on** | The bank quotes thirty currencies and does not quote the rouble, which it suspended in 2022. A deployment that transacts in roubles therefore has a currency with no rate at all, and the honest question is whether that is visible: the refresh reports two sets, codes the feed quotes that the catalogue does not hold (information - the bank is bigger than any one deployment) and codes the catalogue holds that the feed quotes nowhere (a warning, because every obligation in that currency will be refused until somebody adds a provider or drops the currency). Naming an absence is why `CurrencyCatalog` gained an `all()` - a method that exists to be compared against something else rather than to be iterated. |
| **A stated bound that only one writer checks** | `docs/specs/fx.md` section 2 says `rateScaled` is 1..10^12, and nothing enforced it: not `FxRate`'s constructor, not a CHECK constraint. It went unnoticed for as long as every rate was built by a test fixture under 10^4, and deriving rates from a provider's document is what made it matter - the value now arrives from outside the process. `CrossRates` refuses a derived rate outside the bound (with a message naming the provider and the pair); the type and the database still do not, and that half is recorded in the spec's open questions rather than half-done here. The tell is general: a bound written in a table is not enforced anywhere until something compares it against a writer. |

| **A format is a substitution, not a conversion** | The client's money formatter takes the digits from the integer by slicing its string and the punctuation from `Intl.NumberFormat.formatToParts` - and the two probes it uses are not interchangeable. `formatToParts(0)` has no group separator in it, because zero does not need one, so trusting it produced ungrouped amounts in every locale; and the secondary group size is the *last but one* chunk of a long probe, not the first, because in `en-IN` the leading chunk has a variable length - which grouped 123456 as `1,2,3,4,567`. Both were found by the property loop over six hundred amounts, and neither would have been visible in an example test that asserted one English number. The general shape: when a value is exact and only its *presentation* is delegated, the delegated part still has to be checked against something, and the something is a generator rather than an eyeball. |
| **A test can pass by luck, and a fresh clone is what finds it** | The test for the run list's tie-break drew two random UUIDs and predicted the page's order with `UUID.compareTo`. Java compares a UUID as two *signed* halves; PostgreSQL compares its `uuid` type as unsigned bytes. The two orders agree for roughly half of all pairs, so the test passed in the working tree and failed on the first cold clone - where the same code is run from scratch, with different randomness. The fixture now gives explicit small ids, which compare the same way under both rules, so the expectation is a fact rather than a guess. The general lesson is not "avoid UUIDs": it is that an assertion which *derives* the expected answer from the same kind of value it is checking is not testing the order, it is testing a coincidence - and the only cheap way to notice is to run the suite somewhere that is not the tree you have been working in. |
| **A cold start is not a slow assertion, and the suite measured the machine** | Three netting-preview tests failed on the fresh clone's first browser run and passed on the immediate re-run: `vite preview` compiles and serves the bundle on first request, and the default five-second assertion ceiling was reached before the first response arrived. Nothing about the screen was wrong. Two things came out of it: the assertion ceiling is now fifteen seconds with the reason written down (a cold machine is not a failing assertion, and a wrong assertion still fails), and the config's `reuseExistingServer` - which is what let every working-tree run be fast - is now documented as the trap it is: `vite preview` serves `dist`, so a *failed* build leaves the previous artefact in place and the suite tests the last build that succeeded. A pipeline that starts from nothing removes that possibility, and there is still no pipeline in this repository. |
| **A gap between two complete-looking sides is invisible from either side** | Every piece of the approval path was built and tested: the run report (ADR 0021), the decision write (ADR 0020), the gates (ADR 0023), the client (ADR 0028). What was missing was a way to *find* a run, and it is not visible in the API (`GET /runs/{runId}` and `POST .../decisions` both exist and work), nor in the client (there was no runs screen to notice the absence in), nor in the contract's endpoint list - only in the *flow*: a member, a plan, and no way from one to the other. `V501` had even created `settlement_runs_by_group`, an index for a read nothing performed. Both ADR 0029 and ADR 0030 found their work this way, which is the recommendation: walk the product's flows against the contract, rather than the endpoints against their tests. |
| **A test-framework detail can make a correct screen look broken** | The settlement stub registered `**/v1/runs/*`. Playwright's `*` does not cross `/`, so it matched `/v1/runs/{id}` and not `/v1/runs/{id}/transfers` - that request went to the dev server, came back as something that is not a transfer list, and the screen (correctly) showed a problem notice where the test expected a table. The test reported "element not found", which points at the screen. The fix was a `**` pattern and one handler that dispatches on the path, because "register the specific route after the general one" is an ordering nobody remembers to keep. The general shape: a stub that silently lets a request through converts a stub bug into a product bug report, so a stub should fail loudly on a path it did not expect. |
| **The locale gate is coarse exactly where a key is built from data, and that is the right trade** | `I2` matches `t('...')` literals and template prefixes. Keys built from an enum value are legitimately invisible to it (`t(\`netting.direction.${direction}\`)`), so it counts the whole prefix as used. That coarseness then hid a real defect: the status catalog was written in camelCase (`awaitingApproval`) while the status word is `awaiting_approval`, so the screen rendered the **raw key** as a badge and `I2` passed. The browser test caught it. Two lessons in one: a prefix-matched key needs a test that renders it (now true for every status), and when a gate cannot see something, the thing it cannot see needs a gate of its own. |
| **A key kept in a table is a key the gate cannot check at all** | The first `model/decision.ts` held `{ key: 'settlement.status.awaitingApproval', tone }` per status and the component rendered `t(status.key)`. `I2` could not see any of those keys, and the check failed rather than passing - the good outcome. The fix was not to loosen the check but to change the design so the key *is* the status word (`` t(`settlement.status.${status}`) ``), which put the vocabulary's one home in the migration's `CHECK` constraint and made the keys visible. |
| **A graceful degradation can hide a broken mechanism for a whole slice** | The client's catalog loader walked the *bundler's module namespace* rather than the JSON document, and a JavaScript identifier cannot contain a dot - so `localeName.en` and every error code in `errors.json` (`api.decision_already_answered`, `fx.rate_stale`, ...) had no named export and lived only under `default`. The loader therefore keyed them as `common.default.localeName.en`, and since ADR 0028 **every server error message in the client had been missing**: `ProblemNotice` degraded it into "this screen has no wording for the reason yet" plus the code, which reads like a small gap in the catalog. Nothing failed, no test went red, and the check that *looks* like the guard for this - the catalog tool - reads the JSON files directly and so could never see it. The fix was two lines, and the lesson is the expensive part: a fallback that turns "missing" into "unexplained" does not announce itself, so the assertion has to name the *dotted* key rather than a key in the shape the loader happens to produce. The same loader had a second bug in the same area (its glob never matched `locales/_meta.json`, so the review state was empty and every locale was treated as unreviewed, which made the review-state test pass for the wrong reason). |
| **A missing parameter must not take the screen down** | `ledger.currency_mismatch` is documented as carrying `{expected}` and `{actual}`, and a response without them - a test stub's, in this case - made `IntlMessageFormat.format` throw inside a render. React unmounted the tree and the member saw a blank page. The formatting call is now wrapped: a message that cannot be formatted is returned with its placeholders visible, which is worse than a finished sentence, much better than nothing, and self-diagnosing because the placeholders name what is missing. The defect was found by a browser test that expected a *refusal to be visible* and found no page at all - which is the argument for asserting the presence of a message rather than the absence of a crash. |
| **A name that a second place has to agree with is a bug waiting to be reported** | The optimistic row was inserted into a cache key the mutation *named* ("the open, unfiltered view") rather than the one the screen *used* - an unfiltered screen keys its query with `null` filters, so the provisional row went into an entry no component read. The browser test that asserts the pending row exists found it. The fix is the same rule the backend applies to duplicated logic: derive the key from the query factory both callers share, so there is one definition of which list is which rather than two that must stay in step. |
| **A digit lookup is a trap for anything that is not a digit** | Mapping digits to a locale's numbering system by looking each character up in a ten-entry table turned the grouping *space* into a zero, because `Number(' ')` is `0` and the table had an entry for it. `1 234,56` became `10234,56` - a wrong amount, not a wrong format, produced by a one-line utility that looked obviously correct. Caught by I5, which asserts the digits of the output are the digits of the integer. The lesson is narrower than "test everything" and more useful: a lookup keyed by a coercion is a lookup with an entry nobody meant to add. |
| **A fallback has to be in one language** | When a key is missing from a locale, the English message is rendered - and formatting that English sentence with the *reader's* plural rules produced "1 currencies": Japanese has one category, so the English `one` form was never selected. The fallback is now English all the way down, because plural categories belong to the language the sentence is written in. The alternative - showing a half-translated locale's grammar collapse into nonsense - is worse than showing English, and both are worse than the translation being reviewed, which is what the notice on the screen asks for. |
| **Two providers, one document attribute, and React decides by effect order** | The shell provides the URL's locale and a money screen provides its own with `requireReviewed`; both wrote `lang` on the document, and a child's effects run before its parent's, so the outer provider won - English text announced as Russian to a screen reader. The rule is now explicit (only the innermost provider writes `lang` and `dir`) and it was found by a browser test that asserts the notice and the attribute *together*, which is the combination that matters: either one alone would have looked fine. |
| **A copy is correct until the next change** | `ExecuteRunService`'s "what is a run whose transfers are in these states" was five lines, and the resolution use case needed the same five. Copying them would have been *correct on the day* - which is what makes the decision hard rather than obvious - and wrong the moment a ninth transfer state or a new run status appeared, because a switch over a vocabulary is a function of that vocabulary and only one of the two copies would be taught about it. The rule is worth stating: the moment to extract something is when the *second writer* appears, not when the copies drift, because drift is invisible until it is expensive. The tell is the phrase "it's only five lines" - length is not what makes a duplicate safe; having nothing about it that can change is. So `RunOutcome` was extracted and `ExecuteRunService` calls it too, with both writers reading a run back from its rows before asking. |
| **A refusal can be the answer** | When a refusal is what a caller meets *because* they retried, it has to carry the fact that stops them asking: `TransferAlreadyResolved` names who decided, when and on what basis, so an operator whose command timed out learns that the stored answer is their own rather than a colleague's - and an operator who is told nothing learns nothing. A bare "already resolved" would have been true and useless; a silent no-op would have been worse, because a *contradicting* answer would have vanished without trace. Generalised: for a command line, the refusal's message is part of the interface, and it should carry the data the caller needs to stop rather than the minimum needed to be correct. |
| **An unattributed claim has to be unbuildable** | "An attribution rather than an authentication" is only honest if the attribution is *required*, so `by` and `basis` are non-optional in `TransferResolution` and the command refuses to run without them. A nullable column would have been filled on the happy path and empty on the night it mattered, and its emptiness would have been invisible - a resolution with no name reads exactly like one whose name nobody queries. The price is visible (the highest-stakes command in the module has five options, and an operator's shell gets longer) and that price is the mechanism rather than the cost. It also puts the rule where it cannot be forgotten: enforced when the value is built, so no use case can skip it and no adapter can go around it - the same arrangement as the transfer's other invariants, which is what made "the silence is cleared" impossible to omit as well.
| **A retry count is a duration in disguise** | Escalation needed "how long has the rail been silent", and the cheap way to get it was the count already in the rows: "escalate after eight unanswered calls". It needs no migration, and it is a policy written in the units of a *different* policy - with an exponential backoff, eight tries means thirty seconds, or two hours, depending on `retry.base` and `retry.cap`. So retuning the retry backoff would silently move the escalation line, which is exactly the class of coupling this project keeps refusing (two derivations of one fact, a rule living behind a repository). The tell is worth remembering: a threshold expressed in attempts *reads* like a duration to whoever knows the backoff and like nothing to anyone else. `V508` stores the instant instead: one column, and the policy is stated in the unit it is a judgement about. |
| **A test about "nothing happens" has to watch where it would show** | The failed-run guard was found by a test that asserted the number of calls the *rail* had received, not the run's status. That mattered: with the hole open, the second pass moved no status (the run was already `failed`), so every assertion about the run's own rows would have passed while money was being offered to a rail for a cycle that had been declared over. "Nothing should happen" is only testable at the boundary where the something would appear - for a worker, that is usually the rail's call log or a row that must not change, and it is worth naming in the assertion (`.as("a run that failed is finished: resuming it would move money...")`) so the next reader knows what the test is protecting. |
| **A projection that outlives its subject has to carry its subject's version** | The gate conclusion is written once per look and the run keeps moving - a run that was `awaiting_approval` when the gates were read can be `done` a second later. Without the status the verdict was reached for, that finished run would report "one approval short" for ever, and every reader of it would be right to believe it. The fix is one cheap column and one sentence in the contract: a stored judgement about a moving thing records which version (or which instant) it was about, so a reader can date it. The same shape appeared twice in this project already - the refusal's `next_attempt_at` beside the count it was derived from, and the earlier decision to store a run's status as a fact rather than derive it - and it is worth naming as a habit rather than rediscovering per table. |
| **The tempting storage shape lost to a fact that already existed** | "Which members still have to confirm?" first looked like a schema question: a `uuid[]` column (this schema's first array, and no `CHECK` would ever constrain it) or a child table refreshed on every look. Both were dropped once the question was asked properly - *the run's member set already lives in `net_positions`* - and a per-member list here would be the second place it is written, with a refresh cycle nobody asked for. The count turned out to be what the rule needs to explain itself ("enough approvals, and a debtor still has not confirmed"), and *who* is a different gap with a better fix (publish the participants). A decision that looks like "which shape do I store this in" is often answerable by "does this fact already live somewhere, and would this be a second copy of it". |
| **The test that was pinning the hole** | A test written to assert the symmetry of the two answers - "a rejection is a state rather than the absence of an approval" - used a rejecter who was not one of the run's members, and it passed for as long as the rule had the same gap: `Quorum.rejected()` read the raw rejecter rows while `countedApprovals()` intersected them with the run's participants. So `QuorumTest` was *about* the right rule and pinned the wrong one, because it never had to build the case where the difference shows. The new test asserted the same symmetry properly, failed on its first run, and turned up a live safety hole: with `memberId` a client-supplied claim, one decision row from anybody could end any group's run. The lesson is not "write more tests" - there were plenty - it is that a test written from a rule's *wording* can miss the rule's *scope*, and the scope is where guards live. ADR 0016 now carries the amendment. |
| **Nothing is released, so renaming is free - and that is an argument for renaming now** | The answer endpoint was called `POST /runs/{runId}/approve`, and it had to stop being called that the moment it could record a refusal: a name is what a client developer reads first, and a path that records refusals while being named for the other answer gets copied into a frontend, a doc and an SDK before anyone notices. The rename cost fifteen files of mechanical churn and left the request body compatible (an absent `decision` still means approve), which is what makes it cheap: the *word* was wrong, not the shape. The same reasoning had already moved two error codes one slice earlier; names are the cheapest thing to fix before there is a client and the most expensive afterwards. |
| **A shared test double leaks, and the failures land in the wrong tests** | The rail used by `ExecuteRunTest` is a singleton Spring bean, which is what lets a test assert "the rail saw this once". The cost appeared immediately: the test that restricted the rail to INR so an unsupported currency could be refused left it restricted, and tests running afterwards failed with `RailCannotSettle` for a currency they had every right to settle. The reported failure named the wrong test, which is the part worth remembering - a fixture that carries state across tests produces failures that read as product bugs in code nobody touched. Fixed by resetting the double rather than by ordering the tests. |
| **A rule enforced by a test in another module could not be claimed at all** | `RuleCoverageTest` resolves each "enforced by" reference by reflection, and reflection cannot see another module's test classes - they are not on its classpath. So there was no way to write "S1 is enforced by `ExecuteRunTest`", and the map filed S1-S5 as *deferred* while the code enforced them: the failure this whole file exists to prevent, committed by the file itself, in the direction that reads better. Behavioural rules simply could not be claimed. The map now resolves a fully-qualified test name by reading the source it names and requiring an `@Test` method with that name, which is the same technique `SchemaOwnershipTest` uses for migrations - some facts are about a repository rather than about a JVM. Verified by sabotage: naming a method that does not exist fails the build. |
| **A test fixture can quietly become a second implementation of the thing under test** | The cross-currency fixture wrote the converted amount and the rounding residual as literals in an `INSERT`, with a comment explaining that these were what the rate produces. That made the fixture a rival to `libs/fx`: if the library's rounding ever changed, the fixture would keep asserting the old numbers and the two would disagree in silence - and the rows it wrote would claim a conversion nobody performed. It now builds the quote through `FxQuote.lock` and inserts the object's own fields, and it *refuses* when the amount the test asked for differs from the amount the library computed. The number is unchanged; what changed is that only one thing computes it. |
| **Two architecture rules I had read, and still broke - and the guards' own comments described the fix** | This slice's first full build failed on `ModuleBoundaryTest`, twice, both times on rules the slice's design notes had already cited. `b15_transactional_only_in_application` refused a `@Transactional` I had put on the new read adapter as a defensive redundancy - the use case had opened the transaction already, and a commit in an unexpected layer is a commit nobody reviewed. `b20_api_has_no_way_to_localise` refused `Locale.ROOT` in a `wireName()` that lower-cased an enum constant - and the rule's comment spells out the better mapping: write the wire values out, so a rename fails the build instead of silently changing a field a client reads. Both were fixed by deleting something rather than adding something. Recorded because "the guards will catch it" was true and is not a plan: the value was in how precisely they said *what* was wrong, which is only true when a rule has a stated reason (here, both do). |
| **A read model's honesty is measured by what it refuses to compute** | The contract's `SettlementRun` asked for four things that are judgements rather than rows: whether quorum is met, the group's external FX requirement, reporting-currency totals and the instant a run executed. Three of them were reachable with a little arithmetic - quorum is a pure function over the run's own rows (ADR 0016), the requirement is the plan's clearing residual, `executedAt` could be the last confirmation - and each would have been a second implementation of a worker rule, published to members by the module that does not move the money. The interesting part is not the restraint but how it is kept: the absences are *asserted* in `ReadRunApiTest`, so populating a field has to break a test rather than slide into a response. A comment would have decayed; a failing assertion does not. |
| **A code identifies a condition, not an endpoint - and the second caller is when that shows** | `api.no_such_run` and `api.request_incomplete` were named after the endpoint that first needed them. The read endpoints need exactly the same two conditions - a run that does not exist, a request that is malformed - and RULE B20 makes a code an identity clients branch on, so keeping two codes for one condition would be asking them to handle both. They became `api.no_such_run` and `api.request_incomplete`. The general shape: an error code with its first caller's name baked in is a coin-flip, and the second caller settles it. |
| **The same word read and written are not the same decision** | This module *writes* one word into the worker's table (`'approve'`, fixed by a `CHECK` constraint and read by the run's quorum) and *reads* many words it does not own (eight run states, five transfer states). The write is a claim and must be owned; the read is a projection and must not be - a reader that mapped statuses into an enum of its own would refuse to read a run from a worker that had learned a ninth state, which is a read model that breaks when the thing it reads is upgraded. Same column, same table, opposite duty. |
| **Two tables with the same column names do not join by accident** | The partition read wanted a transfer count per currency, which looked like a `left join` with `count(*)`. jOOQ rendered it happily as `on (run_id = run_id and currency_code = currency_code)` - true of every row and meaningless - and Postgres refused it at runtime rather than the compiler at build time, because the columns are named rather than typed. Two reads inside the one transaction `ReadRunService` had already opened give the same consistency, say what they mean, and need no qualification to be correct. |
| **A contract can specify an operation that cannot be implemented, and the compiler will not notice either** | `POST /runs/{runId}/approve` was in the OpenAPI contract from the beginning, with a path parameter, a `200 Approval recorded` and no request body. A quorum is a count of *members*, so an approval by nobody is not an approval: the operation needed one field and had no way to carry it. This is the second time in two slices that a written claim outran the shape that had to carry it - the rail port's javadoc last time, the contract's request body this time - and the shape of the finding is the same. A specification is read as a plan and its omissions are read as simplicity, so the useful habit is to ask of every operation what it needs that it does not say, before concluding it is nearly done. |
| **A rule that is a pure function over rows is what makes a second writer safe** | ADR 0016 made a run's quorum a pure function over the run's own stored rows, for testability and for the reason that a quorum any member id could satisfy is not a quorum. Nobody was thinking about cross-deployable writes when that was decided. Two slices later it is what lets `apps/api` - which may not depend on `workers/settlement` (RULE B6), with no broker in the build - record a member's approval by writing a row: the row's meaning is computed from it, so a row written from outside is judged by the same arithmetic as one written by the module's own use case, and an answer from a member the run does not concern is inert whoever wrote it. The lesson is not "pure functions are good" but that the *shape* of a rule decides what protocols are needed later - and the cheapest protocol is a row nobody has to interpret. |
| **"Nothing moved" is an assertion about the ledger, and it is easy to write one about something else** | The first version of the worker's approval test asserted that a quorum-less run settled no commitments by counting `settlement_run_obligations` - and failed, because that table links a run to its obligations when the run is *computed*. The assertion was about the plan rather than about movement, and it would have read as a passing check on any run that executed later. Naming what "moved" means (the booked entries) is the fix, and the general form worth keeping: an invariant assertion should name the table that carries the invariant, not a table that happens to be empty in the fixture. |
| **Which constraint refused a row is visible only through a translation this module already had** | Refusing an answer from the group's clearing account needed the difference between "a duplicate" and "a check constraint said no", and the obvious implementation - read the constraint's name off the driver's exception - does not compile in a module that does not depend on the Postgres driver. Spring's translation was already there: SQLState 23505 arrives as `DuplicateKeyException`, everything else integrity-related as `DataIntegrityViolationException`, so the classification is two catch blocks and no driver types. Recorded because the first instinct (widen the dependency) was a *compile*-time convenience for a *runtime* fact, and the test is what proved the mapping actually happens. |
| **A service nothing calls is indistinguishable from a service that works - and this worker did not even stay up** | Every use case in `workers/settlement` was tested and reachable, and nothing in a deployment called the last one. The tests hid it because a test *is* a caller: `ExecuteRun` had twenty assertions and no production caller, so "settlement executes" was true of the code and false of the product. The sharper fact, found while fixing it: a non-web Spring Boot application whose context has no non-daemon thread exits when `main` returns - so `java -jar` on this worker refreshed a context, ran its runners and stopped, and would have done that even with every rule in force. A scheduled task is now the thread that keeps it alive. The general lesson is worth keeping: a test suite cannot tell you that something triggers your code, and the question "who calls this in production?" has no assertion that can stand in for it. |
| **A signature can promise less than its javadoc claims, and the compiler will not notice** | `PaymentRail.submit` was documented as returning "the rail's reference, or a failure classified as retryable or terminal", and the type was `Result<RailReference>` - which cannot carry a classification. The claim was not wrong in intent: the caller *did* infer one, reading every `Result.fail` as retryable and every `failed` status as terminal. That inference is correct for the mock and wrong for any adapter that answers a 400 with a failure, so the spec's requirement was satisfied by luck and by an accident of which adapter existed. The repair was a sealed pair of refusals in the port, and the lesson is narrow and useful: when a javadoc uses a word the signature has no room for, that word is a requirement that has not been met yet - and the compiler is not going to mention it. |
| **A test facility is worth building when the behaviour is about time** | The backoff and the breaker are decisions about *when*, and the tests would have been sleeps at best and unwritten at worst. What made them cheap was a rule already in force for other reasons: nothing below `application` reads a clock, so a test can replace the clock *bean* and move time. It found a second thing on the way - a clock reading nanoseconds cannot assert against `timestamptz`, which keeps microseconds - and that is why `MovingClock` truncates. Two of the three new classes need no container at all, because the arithmetic (`BackoffTest`) and the breaker (`CircuitBreakerTest`) are pure decisions with a clock passed in. |
| **`@DynamicPropertySource` outranks `@SpringBootTest(properties)` - and quietly** | A per-class property override was silently losing to a base class's dynamic property, so a test configured with a two-failure breaker threshold kept the base's hundred-thousand and reported failures that belonged to the configuration rather than the code. Property precedence in Spring tests is real, documented, unhelpful to read at a glance, and invisible when it goes wrong: the test fails with a plausible-looking assertion error. Recorded because the fix (do not register a property dynamically when a subclass may want to override it) is a rule for this repository's tests, not a Spring detail. |
| **State that is deliberately process-wide makes the test expensive, and that is the honest price** | The breaker's counters live in memory for the life of a process, which is what lets a restart recover a deployment - and it means a test that trips it decides the behaviour of the next test in the same Spring context. Rather than add a reset method that only a test would ever call, the class that trips it owns its context (`@DirtiesContext` per method). Two ordinary tests, two context starts, and a design decision left intact - worth recording because the tempting alternative would have put a production API in the service of a test. |
| **A threshold belongs where the work is, and the spec had put it one level down** | `V503` promised `submitted_at` on `settlement_transfers` for the reconciliation sweep, with a partial index. Building the sweep showed the unit was wrong: `ExecuteRun` already walks a run's transfers and decides for each whether to submit or poll, so a per-transfer timestamp would be a second mechanism for what the pass already does - and the guard that was actually missing was against *running the same run twice*, which is a fact about the run. `V505` puts the column there and records the correction, including why V503's promise is not broken by it. A spec that describes a mechanism in enough detail to be built is useful even when building it shows the mechanism was one level off; what would have been wrong is shipping a column nothing reads because the document said so. |
| **An operator's command line is a surface, and what it refuses to do is part of it** | The command line exists to trigger what is not automatic, and the interesting decision was what to leave out: `DecideOnRun` records a member's approval, and an operator command that could do it would settle a group's money with a keystroke no member made. So the command has `compute`, `drive` and `fund`, and the end-to-end test has to record approvals through the port - which turns the missing member surface from a note in a document into something a test walks into. Its exit codes carry the same intent: `0` done, `1` the domain refused, `2` the invocation was wrong, because a cron job that treats a refusal as a crash and a crash as a refusal will do the wrong thing quietly. |
| **A read model across a module boundary makes the test database the other module's business** | The API's preview now excludes commitments a settled run discharged, so it reads `settlement_run_obligations` and `settlement_runs` - and `apps/api` may not depend on `workers/settlement` (RULE B6), so those migrations are not on its classpath. A deployment is fine (whichever deployable starts first applies every migration), but a test container built from the API's own classpath has no such tables, and the query fails with a missing relation rather than a wrong answer. Fixed by pointing Flyway at the worker's migration directory - the exact mirror of what the worker's tests have always done with `V101__obligations.sql`. Worth recording because the symmetry was invisible until a read model crossed the boundary in both directions. |
| **Settling cannot be derived from an instant, and P4 is why** | The tempting way to mark a commitment settled is temporal: a `done` run for this group exists at or after the obligation was incurred, so it is discharged. It needs no link table, no id plumbing through the planner, and it is wrong. `incurred_at` is supplied by the client (P4) and may be backdated, so an obligation incurred last Tuesday but recorded today, after a run over the whole of last week, would be derived as settled by a run that never saw it - un-settleable for ever, and invisible until a member asks why their debt is still open. The ids the run actually netted are recorded instead, which is one table and no guessing. |
| **Two copies of a lock-backed query are not two copies of a comment** | The chain-head read exists twice: once for the API's obligation bookings and once for the worker's settlement bookings. Extracting it needs a home that does not exist (`libs/ledger` is pure and cannot hold a query; `platform/persistence` owns no behaviour and would acquire knowledge of the ledger's tables), and section 12 says a new home is an ADR. So it is duplicated with the risk written down in both copies: if the two drift, one may lose its advisory lock, and a missing lock does not fail in review - it forks a chain under load and nothing notices until a verifier runs. The trigger for extracting it is a third writer. |

| **An invariant that was true only as long as two acts were one act** | `JooqRuns.describe` threw on a cycle with no run over it, in words that named the reasoning: *"The two are written together, so this means a run row was deleted rather than that a cycle was opened without one"*. That was true, and it was true *because* opening a cycle and computing it were the same call - so separating them (ADR 0032) turned a corruption signal into an ordinary state, and seven of the new tests failed in the adapter before passing in the new code. The general form is worth keeping: when a slice splits two things that were one, the invariants that held *because* they were one must be found deliberately. A grep for the sentences that justify a check by "the two are written together" is the cheap version, and it is what found this one. |
| **An assertion can be written from a design's intent rather than from its behaviour** | A test of the batch bound asserted that a pass of one drains a queue of two to empty on the third pass - and failed, because a cycle that cannot be computed is *supposed* to stay in the queue (a backdated obligation can make it computable later, so the pass looks at it every time). The code was right; the assertion came from what the design intended to achieve rather than from what it does, and it took a failing run to see the difference between "the queue drains" and "the queue drains what it can". This is the same failure mode the plan already records for invariant assertions that name a table that happens to be empty in the fixture: the assertion has to be about the mechanism, not about the story. |
| **A join turns a statement's field constants into an assumption** | The cycles read was written reusing the same `DSL.field(...)` constants the insert used - unqualified names, which is all an insert needs. Adding the left join over `settlement_runs` made `id` ambiguous at runtime (`column reference "id" is ambiguous`), which `javac` could not see and a reviewer would not: the constants are `static final` and nothing about them says which tables the statement will contain. The adapter now keeps two sets, qualified columns for the reads and unqualified ones for the insert, and says why in a comment. The general form is worth keeping: jOOQ's untyped `Field` constants carry no reference to a statement, so their correctness is a property of every query they are used in rather than of their own declaration. |
| **The same read argument arrives twice, and the second time it is about asking rather than answering** | ADR 0030 added `GET /groups/{groupId}/runs` because a member could not answer a plan they could not find; ADR 0034 adds `GET /groups/{groupId}/cycles` because a member could not wait on a request they could not see. Both are the product half of a flow whose endpoints already existed, and both were named as gaps in the ADR that built the write - which is the pattern working: a slice records what it did not do, and the next slice's subject is already written down with its reasoning. The counter-example is worth noting too: the *reads* were cheap (one query, one schema, two days of assumptions), so the temptation to skip them was real and would have been wrong twice. |
| **A promise in a contract is a bug both suites agree is fine** | `GET /groups/{groupId}/members` sat in the contract for seven slices with nothing behind it, and the client called it from three screens the whole time. Both suites were green: the client's browser tests stub the API *from the contract's own types*, so a stub generated from a promise passes; the server never asked for members, so it had nothing to implement. The only visible symptom was in development, where every screen that names a person fell back to the uuid its `nameOf` documents as the honest fallback - a fallback that made a broken read look like a deliberate one. **And this instance was not alone.** Auditing the contract's thirty operations against the six controllers afterwards found three more reads the client calls and no handler serves: `GET /v1/obligations` (the obligations *list* - the app's main screen, which has therefore never worked against a real API in this build), `GET /currencies` (the catalogue every money-rendering component needs an exponent from) and `GET /fx/rates` (the rate the recording form locks). Plus the ones the plan already knew about: balances, tax, groups, activity, invites, auth, execute, void, webhooks. **Two wrong claims were made on the way to that list, and both are worth more than the list.** The first is the commit message this slice was originally pushed with: "Every operation the contract promises now has a handler, except the ones removed rather than faked" - written before anything had been audited, and false the moment it was. The subject of a commit is a claim, and this one was a claim nobody had checked. The second is the audit's own first version, which read a mapping's parenthesised arguments as a path, so `@GetMapping(produces = ...)` was recorded as an operation at a path that does not exist and the report cried wolf. The lesson is the same in both directions: a promise nobody implements is a gap the document hides, and the tool - or the sentence - that goes looking for such gaps is not evidence until it has been made to fail on a case whose answer is known. The fix is a check that walks the promised operations, matches them against `@RequestMapping`/`@Get|Post|Put|Patch|DeleteMapping`, and *reports* the difference with each gap's owner (a plan row or an ADR) - failing only when a new gap appears unlisted, the way the locale check reports ru rather than failing on it. **Built as `ContractCoverageTest` three slices later (ADR 0038)**, which is late enough that the four reads it was written about had already been closed by hand: the check now records the seventeen gaps that remain, one of them a removal candidate rather than a screen, and it fails in three directions - a promise with no handler and no owner, a recorded entry whose handler has landed, and a handler the contract does not declare. It was made to fail on all three before it was trusted, and it produced two false reports of its own on the way, both now written into its documentation. |
| **PostgreSQL does not resolve a select alias in a `WHERE`, and jOOQ does not rename a derived table's columns** | The status a list serves and the status a `status=` filter compares have to be one expression, or a page can contain a row whose status it would not display. Two shapes were tried and both failed at the database rather than at the compiler. A derived table (`from (select ..., case ... as status) page where page.status = ?`) names every column after the table in jOOQ's metadata - `"page"."incurred_at"` - so eleven more constants would have been needed and the by-name mapping became impossible, while the derivation still had to appear inside the select. Filtering on the alias directly (`select ..., case ... as status ... where status = ?`) is a syntax error, because a select alias is not visible in `WHERE`. What works is one Java expression rendered twice: `STATUS_EXPRESSION` for the condition and `STATUS_EXPRESSION.as("status")` for the column. The general lesson is about *where* a query gets checked: this one looked right in both wrong versions and was wrong in the SQL text, which is only ever visible by running it. |
| **A comment that keeps the code consistent with itself rather than with the world** | `ObligationStatus` said, in its own javadoc, that `NETTED` and `SETTLED` were "not producible until the worker exists" - a true sentence when it was written and false from ADR 0032 onwards, because the worker then existed. Meanwhile the read path derived the status from `voided_at` alone, so an obligation a settled run had discharged was reported `open`, and the sentence in the enum made that look deliberate: the code and the comment agreed with each other and disagreed with the database. Nothing forced a second look, because nothing reads a javadoc. The general shape: a comment that explains *why* code is limited is a claim with a lifetime, and the lifetime is the thing that changes - so the useful version names what would make it false ("until the worker exists") rather than asserting it is still false. |
| **An architecture rule caught a bug the compiler could not** | `toUpperCase(Locale.ROOT)` in an API adapter, written to avoid the Turkish-`i` bug, was rejected by `b20_api_has_no_way_to_localise` - no `java.util.Locale` anywhere in `io.reductxn.api..`, because "a server that cannot see a locale cannot render one" (ADR 0014). The rule was right twice: the locale-sensitive conversion should not be there at all, and the four wire words already had exactly one owner - an exhaustive `switch` in `ObligationMapper` whose comment says a new constant must fail to compile rather than appear in a response. That mapping moved onto `ObligationStatus` itself (`wireName()`/`fromWire()`), and the boundary, the list, the response mapper and the replay path now share it. A rule that forbids a *class* rather than a call is crude and it earned its place here: it is what made somebody ask where the vocabulary lived. |
| **A list a person maintains, with a comment claiming it cannot be forgotten** | `DatabaseTest.resetSchema()` deletes the tables it knows about, and its own documentation said truncating "is both simpler and impossible to forget". `group_members` was the first table added since that sentence was written, and forgetting it was silent: the rows leaked into the next test class, whose fixture assertion ("an empty list" that had one member in it) failed. That is the right failure to have - a test failing is better than a table not being cleaned - but it points at the wrong method, and the comment had been asserting the opposite for several slices. The fix is one line and one corrected comment; the lesson is that a sentence in a comment is not a mechanism, and the honest version of it says *why* the list is hand-written (each table's rows are cleaned for a reason the next person has to know) rather than claiming nobody will forget. |


| **A contract node can promise an operation its own schema cannot express** | `POST /groups/{groupId}/cycles` offers `{action: open\|close, periodStart, periodEnd}`. `open` needs the instant that keys a cycle and the body carries dates; `close` has nothing to write to, because `settlement_cycles` has no status column at all; and the `201` has no body, so a client cannot learn the cycle id that `POST /cycles/{cycleId}/runs` needs - and that operation became redundant the moment a cadence did the computing. None of these are fatal and all of them are now written down (ADR 0032, `docs/specs/settlement.md`), because a contract that is read as a to-do list will one day be implemented as written. |
| **A design note that rests on a property of an existing adapter has to check the adapter** | ADR 0032's closing paragraph chose a shape for the API's cycle endpoint - a request table in the API's schema, consumed by the worker's pass - because it assumed an API-written cycle would have to duplicate the worker's id derivation, and a key scheme copied across a deployable boundary is exactly the kind of drift this repository refuses. The premise was false: `JooqRuns.open` inserts its proposed id with `on conflict (group_id, as_of) do nothing` and then *reads back whichever row the pair has*, writing the run against that. The pair arbitrates the cycle's identity and the derivation is only a proposal, so the API can mint its own id and the request table was never needed. Worse, the repository already contained the evidence - `OpenCycleTest.aCycleOpenedNowIsTheCycleComputedLater` mints a random id on purpose - so the cost of checking was one read of an adapter or one read of a test. The habit: when a decision's reasoning turns on how an existing piece of code behaves, read that code before designing around it, and treat a test that asserts the property as the proof it already is. |
| **The careful-looking refusal is often the one that must not exist** | The endpoint that opens a cycle could easily have refused "nothing to settle": the group's commitments are this module's own rows, the port that reads them already existed, and a `422` with a code reads like diligence. It would have been wrong for a domain reason rather than an architectural one - a commitment recorded tomorrow may carry an `incurred_at` inside a cycle opened today, so a cycle that looks empty is a legitimate request for a backdated bill, and the worker (which looks at a refused cycle on every pass, by ADR 0032's own design) is the only place the question has an answer. The general form, and it is worth applying the next time a refusal looks free: ask whether the fact it rests on can *change* between the check and the work. If it can, the refusal is a race with a polite error message - and the honest implementation is to let the module that owns the fact answer when it acts. |



| **A test's fixture could not see the migration it needed** | The worker's tests need `obligations` to exist, and that table's migration lives in `apps/api`, which the worker may not depend on. In a deployment this is a non-issue - two deployables, one schema, each jar bringing its own migrations - but in the worker's test container only its own classpath's migrations ran, so the table was missing. The fix keeps the boundary: the test points Flyway at the API's migration *file* rather than depending on the module, which is exactly what a deployment has without the dependency. |
| **A rule and a README disagreed about where an adapter lives** | `libs/fx`'s README and its port both said rate adapters live in `apps/api/adapter/rate`, and RULE B16 - which keeps every jOOQ type inside `adapter/persistence` so no row type can escape - makes that impossible for an adapter that reads a table. The build said so the moment the provider was written, and the enforced rule won: the adapter moved and both sentences were corrected. Worth recording because the prose had been there longer and read as authoritative; the difference is that one of them runs. |
| **An unavailable rate and a stale one are different answers, and the port's wording hid it** | `FxRateProvider` asks for "the most recent *usable* rate", and a provider that honoured that literally could never produce `fx.rate_stale` - a stale rate would simply not be returned, and the caller would see the same absence as a pair nobody had ever quoted. Those two need opposite client behaviour (retry later, or do not bother), and the contract gives them different statuses for that reason. So the adapter returns the newest rate *at or before the instant and judges it*: expired is `StaleRateException`, absent is `UnavailableRateException`. That reading of the port is stated in the adapter, because it is a reading rather than the port's own words. |
| **Bean-validation annotations would have been silently inert** | `spring-boot-starter-web` does not bring a validation provider, so `@NotNull` on a request-body record is ignored and a missing field arrives as `null` - which is worse than not annotating, because the annotation reads like a check. The controller validates the fields the use case cannot be handed at all, and the reason it is a check rather than a second failure vocabulary is that a malformed request is a `400` while a well-formed request the domain refuses is a `422` with a code. |
| **A gate that has never failed is a gate nobody has tested** | The two drift gates the pipeline adds were each made to fail before they were trusted: a path added to the contract made the spec-sync check exit 1 and print the generated diff, and a route tree taken from an older commit made the route-tree check exit 1 and print the missing route imports; both passed again once restored. This is cheap, and it is the only way to know a check is wired to anything - a `git diff --exit-code` over a file nobody regenerates, or over a file the build rewrites *before* the diff runs, is a step that always succeeds and reads like a gate. The same reasoning is why a prefix-matched catalog key gets a browser test that renders it rather than a check that counts it (ADR 0030). |
| **An honest header is a small liability that is worth keeping current** | `tools/check-catalogs.mjs`'s header said the codegen gate was "**Not built**" and that there was no CI configuration - both true when written, both stale the moment the pipeline existed, and both fixed in the same commit that made them false. A comment describing the state of the world rots; it is kept because the alternative is a check whose relationship to the plan's gate table nobody can see. The habit it suggests: when a slice closes a gap, grep for the sentences that said the gap was open. |




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

**Verified how:** `./gradlew clean build` from a fresh clone, which is the check that previously caught a source
file `.gitignore` was excluding. `apps/api`, `workers/settlement`, `libs/rails` and `platform/persistence` all have
sources and tests now, and every module declared in `settings.gradle.kts` is a real Gradle project covered by the
boundary rules. The modules with no code are the ones whose phase has not arrived: `platform/security` and
`platform/observability` (Phase 3), and `infra/*` beyond the compose stack. `apps/web` is not a Gradle module at
all - it is a pnpm project inside the repository, which is why `./gradlew clean build` cannot see it and why the
client has its own two commands (`pnpm check` and `pnpm e2e`) listed in the gate table above.

This paragraph used to say that `apps/api` and `workers/settlement` "have no sources" and that those were Phase 1
work, which had been untrue for several slices - a stale claim in the document that exists to say what is true.


*End of master plan. Changes to this document follow section 12.*
