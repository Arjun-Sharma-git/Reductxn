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
| Module graph | ArchUnit + Spring Modulith | RULE B1-B22 violated. **Live**: 28 ArchUnit rules plus the schema-ownership test, all verified non-vacuous. Spring Modulith is not wired: ArchUnit covers the module graph, but the living documentation it would generate does not exist |
| Rule coverage | `RuleCoverageTest` - **live** | A RULE declared in this plan *or in `docs/specs`* is neither enforced nor explicitly deferred, a coverage entry is stale, or a claimed enforcement does not resolve to something that runs |
| Property coverage | `PropertyCoverageTest` - **live** | A property declared in a spec has no assertion labelled with its id and no recorded reason, or a test labels a property that no spec defines |
| Import boundaries | `eslint-plugin-boundaries` | RULE F1-F12 violated |
| Money discipline | lint ban on `double`/`float` in domain + ESLint ban on division by 100 | P1 violated. **Live**: the `forbidFloatingPointMoney` task in `build-logic` |
| Import style | `forbidWildcardImports` task - **live** | A wildcard import appears (RULE N1) |
| Tracked source | `forbidIgnoredSources` task - **live** | A source file that `.gitignore` excludes, so it compiles and tests green locally and is absent from every clone |
| FX exactness | jqwik X1-X7 - **live** | A conversion is non-deterministic, the residual is not booked, or the round trip drifts |
| FX isolation | ArchUnit - **live** | A rate is multiplied anywhere outside `libs/fx` (B18, B19). Non-vacuous, and guarded against becoming vacuous |
| Error-code convention | `DomainErrorCodeTest` - **live** | A code is malformed, namespaced by the wrong module, duplicated, or not declared as a constant |
| Locale coverage | catalog check | A key is missing versus `en`, unused, or a literal appears in a component (F10, I1-I3) |
| Error-code parity | codegen check | A server error `code` has no catalog entry, or vice versa (I3). **Not built.** Its server half now exists: every code is an enumerable constant, which is what a catalog check needs |
| Locale money safety | Vitest property tests | Formatting changes the digits, or a `float` enters the format/parse path (F11, F12, I5) |
| Spec sync | codegen diff check | RULE C3 violated |
| Ledger invariants | jqwik property tests - **live for L1, L2, L4, L6, L9**, the rest deferred with reasons | A journal entry does not balance, or an FX link is unpaired (L9) |
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
| 1 | MVP ledger + netting preview, **with i18n scaffolding from the first screen** | `libs/kernel`, `libs/ledger`, `apps/api`, `apps/web` (+ `locales/`) |
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

**The Gradle build runs green.** `./gradlew clean build` succeeds, executing 292 tests, none failing:

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
| `SettlementPlannerTest` | 14 | ADR 0012's worked example, the clearing split, per-currency tax, order independence, refused input |
| `SettlementPlannerPropertyTest` | 6 | **X7** and the composition properties: zero-sum including `fx_clearing`, partition completeness, the two legs netting to the clearing position, reproducibility |
| `ComputeRunTest` | 5 | The settlement worker's whole inbound surface, against a container: a single-currency cycle planned and left `awaiting_approval`, a cross-currency one left `awaiting_fx_funding` with the requirement reported per currency, a retry returning the same run and writing no second plan, a cycle with nothing in it refused rather than recorded as an empty run, and the plan's own transfers applied to the stored positions - which zeroes every participant in every currency (N2, per currency, read back from the rows) |
| `QuorumTest` | 8 | RULE S1's arithmetic, without a database: a majority of the run's own participants, every net debtor personally (including a member who owes in one currency and is owed in another), the clearing account counted as a participant and never as a member, a stranger's approval shown and not counted, and a stable report order |
| `FundingTest` | 6 | RULE S4's arithmetic, without a database: partial arrivals summing, each currency covered on its own, a surplus covering rather than being carried into another currency, and a funding nothing was waiting for reported rather than dropped |
| `DecideOnRunTest` | 8 | Quorum over a container: the members taken from the run's own plan, a majority that excludes the debtor who is paying being refused, a non-participant and the clearing account refused, a second answer refused, and a rejection stored with who made it |
| `RecordFxFundingTest` | 9 | The escaping half of `awaiting_fx_funding`: a cross-currency run blocked until *every* currency is covered, partial arrivals recorded with the shortfall reported, over-funding recorded, a currency the plan never asked for refused, and a retried funding recorded once |
| `ExecuteRunTest` | 11 | RULES S1-S4 against a container and a rail the test controls: nothing reaches the rail without quorum or funding, an approved and funded run confirms every transfer exactly once, a replay asks the rail for nothing new, a refused transfer fails the run while the confirmed ones stay confirmed, a rail that has not answered leaves the run `executing` and is *polled* rather than re-submitted, a rail that cannot be reached leaves the transfer `authorized`, a currency the rail cannot settle is refused before anything moves, a rejected run never executes, and a cycle that nets to nothing is `done` without a movement |
| `SettlementBookkeepingTest` | 6 | What a settled run leaves behind: one `settlement` entry per confirmed transfer carrying the rail reference, verifiable as a chain by `EntryChain`; a replay booking nothing; a refused transfer booking nothing while the confirmed one books; the whole cycle leaving every member flat and `fx_clearing` at zero per currency, read back from the stored postings with `Balances` (L8); a commitment a settled run discharged not open for a later cycle; and a failed run settling nothing, so its commitments stay open |
| `DriveRunsTest` | 9 | The driver, which is the only caller of `ExecuteRun` in a deployment: a run nothing is waiting on reaches `done` with no test touching the use case; a run that cannot progress is left exactly where it was with the gate's own reason and nothing reaches the rail; a run starts the moment its last fact arrives, because a pass is what notices; a transfer the rail accepted and never confirmed is resolved by a later pass (asked, not re-submitted); the same run is not driven twice inside the window; a finished run is never reconsidered; a named run ignores the window and is the operator's act; a pass is bounded by its batch size and takes the least recently attempted first |
| `SettlementCommandTest` | 8 | The operator's command line over real beans and a real database: no `--reductxn.command` leaves the worker running; `compute` opens a cycle, prints the plan and reports quorum; a group with nothing to settle is *refused* (`1`) rather than reported as empty; a missing option, a malformed uuid and an unknown command are *usage errors* (`2`); `fund` records an arrival, reports what is still required, and treats a repeated reference as one arrival; and an approved, funded run is settled to `done` from a shell in two commands - with approvals recorded through the port, which is the gap that test makes visible rather than hides |
| `ScheduledRunDriverTest` | 4 | The cadence without Spring or a database: a tick asks for runs whose last attempt is older than the window (never for the runs themselves), a tick that finds nothing is silent, a tick whose port throws does not take the schedule down with it, and a tick with outcomes still returns |
| `BackoffTest` | 3 | The wait between attempts as arithmetic rather than behaviour: doubling from the base, the cap as a floor that a transfer cannot drift past, a base of zero meaning "try again at once", and the three ways a policy that cannot be computed is refused |
| `RailRetryTest` | 5 | A rail that does not answer, and a rail that says no: an unanswered call is counted and its retry scheduled, the transfer is not offered again until its wait is over (a scheduled pass and a manual one alike), the wait doubles with each consecutive failure and stops at the cap, any answer resets the count and lets the run finish, and a refused instruction fails the transfer and the run at once with no later pass offering it again |
| `BrokenRailTest` | 2 | A rail that has gone down: it is left alone rather than called by every pass (the detail says so, in the port's own vocabulary, with the cooldown in it), one probe per cooldown, a probe that fails restarts the cooldown, and a probe that succeeds closes the breaker and lets the run finish |
| `MockRailTest` | 5 | The only rail this build has: what it settles, that it confirms and returns the caller's reference, that a re-submission returns what it already holds (RULE S2 at the rail), and that an unknown reference or an unsupported currency is answered rather than ignored - the latter now with the classification attached, since a currency the rail cannot settle is a refusal that would be refused again |
| `CircuitBreakerTest` | 8 | The breaker against a scripted rail and a clock the test moves: calls go through while the rail answers, the rail is left alone after consecutive failures, one probe per cooldown closes it on an answer, a probe that fails restarts the cooldown, a terminal refusal never opens it (a rail with an opinion is not an outage), a rail that throws is treated as one that did not answer, and a policy that could not protect anything is refused |

| `ReductxnApplicationTest` | 1 | That the API's context starts against a container with every owning module's migration applied - `apps/api`'s obligations, `libs/ledger`'s entries and postings, `libs/fx`'s currencies, rates and quotes, `libs/netting`'s tax rules - which is the arrangement most likely to break silently, since a migration that never ran leaves a schema that looks migrated |
| `MigrationsConfigurationTest` | 2 | That the migration guard refuses to start when Flyway finds nothing, and delegates to migrate when it finds the fixture - against a real Postgres, in a schema of its own |
| `NettingPreviewApiTest` | 6 | The contract's preview through every layer, over rows written with SQL and read back through jOOQ: the single-currency shape, a multicurrency group reported as partitions rather than collapsed into one, a commitment a settled run discharged being excluded, an empty plan, framework errors as problem+json, and `asOf` meaning "open at that instant" rather than "created before it" |
| `ProblemTranslatorTest` | 2 | B20: the response carries the domain's code and nothing a client should render, and an un-namespaced code cannot reach a client |
| `RecordObligationApiTest` | 13 | The first write path in the product, end to end. The entry is not inspected but *verified*: the rows are rebuilt as `JournalEntry` objects - which itself enforces L1 and L6 - and handed to `EntryChain`, `Balances` and `FxLinkage`, the same verifiers an offline job runs. Covers the chain joining rather than restarting, one chain per currency, a replayed key answering with the same obligation and booking nothing, the same key in another group being another obligation, the four refusals, and cross-currency: the linked pair written to two chains, the clearing account holding both amounts, the lock's residual, a stale rate refused with nothing written, a pair with no rate at all answered `503` with nothing written, a cross-currency replay locking nothing, and the preview then reporting two currencies - the read path reconstructing the obligation from its quote |
| `DomainErrorResolverTest` | 2 | That the resolver claims domain failures and declines everything else, so a bad request keeps its own status |
| `ModuleBoundaryTest` | 28 | RULEs B1-B5, B6, B8, B10-B16, B18-B22. The four that joined with the settlement worker: B6 in both directions - the first rule whose subject is *both* deployables - and the worker's own B13 and B14 |
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
"not yet gated" since it was written; it is gated now.

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
| FX rates and the residual | ADR 0013 | **Implemented, tested** | 35 tests green. `libs/fx` owns the rate type, the exact conversion and `FxQuote` |
| Per-currency composition | ADR 0012 | **Implemented, tested** | 20 tests green. `libs/planning` partitions a cycle, composes the per-currency plans, and derives the external FX requirement |
| RULEs B3, B4, B5, B10-B17, B18-B22 | ADR 0012, 0013 | **Enforced** | 28 ArchUnit rules plus `SchemaOwnershipTest`, all verified non-vacuous. B3 and B4 had been stated in section 5.1 and never enforced; B11 was described as enforced and did not exist; B12-B15 had no subject until `apps/api` had sources; B16 needed a persistence adapter and B17 a schema; B22 needed the module it constrains; and B6 - the rule the whole `libs/planning` and `libs/booking` story exists to satisfy - needed *both* deployables to have sources, which is what the settlement worker's arrival finally provided. Four rules joined with it: B6 in both directions, and the worker's B13 and B14 |
| `apps/api` preview over real persistence | ADR 0009, 0004 | **Implemented and tested** | 10 tests green against a container: the hexagonal layers, the RFC 9457 error path, the contract's preview reading `obligations` through jOOQ, and the `asOf` semantics that make a past preview reproducible |
| `POST /obligations` - recording and booking | ADR 0004, 0012; RULE B17, B15 | **Implemented and tested, both shapes** | 13 tests green against a container, 25 in the module. The first write path in the product: it records the obligation and books the entries a commitment produces, in one transaction, then verifies the stored chains with `EntryChain`, the positions with `Balances` and the FX pairing with `FxLinkage`. An ordinary commitment books one entry; a cross-currency one books a linked pair through `fx_clearing`, after locking a rate and storing the quote. Idempotent on the contract's required header - a replay answers with what was recorded and books nothing, including the quote |
| The append path and the chain-head adapter | ADR 0004, 0012 | **Implemented and tested** | `JooqChainHeads` takes a Postgres advisory lock per `(group, currency)` before reading the chain's head, and refuses to be asked outside a transaction, because a lock whose lifetime is the transaction cannot protect a read that is not in one. The head is the chain's *leaf* rather than its newest entry by time: `occurred_at` is when a commitment was made, so recording one with a past instant would otherwise be mistaken for the head. `journal_entries_one_successor_per_hash` is the second line of defence, making a forked chain unrepresentable |
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

The books agree with the money (ADR 0017), something makes the money move (ADR 0018), and the moving now behaves
when the rail does not (ADR 0019): a settled cycle leaves every member flat and `fx_clearing` at zero per currency
(L8), the commitments the run netted closed rather than open, and a run that could not be settled says so instead of
retrying until somebody notices.

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
- **A rail that never answers is polled for ever.** The driver resolves what a rail has decided; nothing escalates
  a transfer no rail will ever decide, so section 4.4's "or escalate" is still open. It needs a policy rather than
  a mechanism, and the driver's report is what would feed it.
- **The currency a group brings in from outside is not posted.** The funding is recorded as a fact about the run
  (`settlement_fx_fundings`) and moves no entry, because the entry that would show the group's own contribution
  needs an account for the group - and whether `fx_clearing`, a group account and the tax and fee accounts are
  group-scoped or global is an open question in the ledger spec rather than a detail to guess at.
- **Escalation is not implemented.** A rail that never answers is retried with backoff until the cap and then
  polled for ever: nothing decides when patience has run out, and nothing tells a human. It is now the only half
  of the settlement spec's section 4 still open, and the rows it would read exist - a transfer's
  `unanswered_attempts`, its `next_attempt_at`, and the rail's own words in `rail_detail` - because a policy is
  worth deciding rather than guessing and the mechanism was worth having first.
- **A rail that answers "no" is now reported rather than retried.** A refusal the port classifies as terminal
  fails the transfer and the run (S3), and everything else - a rail that is silent, throttling or unreachable -
  waits out an exponential backoff and is eventually left alone by a circuit breaker in front of the rail (ADR
  0019). What has *not* changed is what a caller does with a transfer the rail accepted and never confirmed: it
  is polled, and the poll never gives up.
- **No rate ingestion**, unchanged: the tables exist, nothing writes a rate, and every cross-currency request
  fails closed.
- **Localization is still Phase 1 scaffolding**, before the first screen exists to retrofit.

The order that follows from that: a scheduler or an operator's command, because nothing settles without one;
then rate ingestion, which is what a deployment actually needs before a group can record a cross-currency
commitment at all; then the client, which has no sources yet.


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
| **A shared test double leaks, and the failures land in the wrong tests** | The rail used by `ExecuteRunTest` is a singleton Spring bean, which is what lets a test assert "the rail saw this once". The cost appeared immediately: the test that restricted the rail to INR so an unsupported currency could be refused left it restricted, and tests running afterwards failed with `RailCannotSettle` for a currency they had every right to settle. The reported failure named the wrong test, which is the part worth remembering - a fixture that carries state across tests produces failures that read as product bugs in code nobody touched. Fixed by resetting the double rather than by ordering the tests. |
| **A rule enforced by a test in another module could not be claimed at all** | `RuleCoverageTest` resolves each "enforced by" reference by reflection, and reflection cannot see another module's test classes - they are not on its classpath. So there was no way to write "S1 is enforced by `ExecuteRunTest`", and the map filed S1-S5 as *deferred* while the code enforced them: the failure this whole file exists to prevent, committed by the file itself, in the direction that reads better. Behavioural rules simply could not be claimed. The map now resolves a fully-qualified test name by reading the source it names and requiring an `@Test` method with that name, which is the same technique `SchemaOwnershipTest` uses for migrations - some facts are about a repository rather than about a JVM. Verified by sabotage: naming a method that does not exist fails the build. |
| **A test fixture can quietly become a second implementation of the thing under test** | The cross-currency fixture wrote the converted amount and the rounding residual as literals in an `INSERT`, with a comment explaining that these were what the rate produces. That made the fixture a rival to `libs/fx`: if the library's rounding ever changed, the fixture would keep asserting the old numbers and the two would disagree in silence - and the rows it wrote would claim a conversion nobody performed. It now builds the quote through `FxQuote.lock` and inserts the object's own fields, and it *refuses* when the amount the test asked for differs from the amount the library computed. The number is unchanged; what changed is that only one thing computes it. |
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


| **A test's fixture could not see the migration it needed** | The worker's tests need `obligations` to exist, and that table's migration lives in `apps/api`, which the worker may not depend on. In a deployment this is a non-issue - two deployables, one schema, each jar bringing its own migrations - but in the worker's test container only its own classpath's migrations ran, so the table was missing. The fix keeps the boundary: the test points Flyway at the API's migration *file* rather than depending on the module, which is exactly what a deployment has without the dependency. |
| **A rule and a README disagreed about where an adapter lives** | `libs/fx`'s README and its port both said rate adapters live in `apps/api/adapter/rate`, and RULE B16 - which keeps every jOOQ type inside `adapter/persistence` so no row type can escape - makes that impossible for an adapter that reads a table. The build said so the moment the provider was written, and the enforced rule won: the adapter moved and both sentences were corrected. Worth recording because the prose had been there longer and read as authoritative; the difference is that one of them runs. |
| **An unavailable rate and a stale one are different answers, and the port's wording hid it** | `FxRateProvider` asks for "the most recent *usable* rate", and a provider that honoured that literally could never produce `fx.rate_stale` - a stale rate would simply not be returned, and the caller would see the same absence as a pair nobody had ever quoted. Those two need opposite client behaviour (retry later, or do not bother), and the contract gives them different statuses for that reason. So the adapter returns the newest rate *at or before the instant and judges it*: expired is `StaleRateException`, absent is `UnavailableRateException`. That reading of the port is stated in the adapter, because it is a reading rather than the port's own words. |
| **Bean-validation annotations would have been silently inert** | `spring-boot-starter-web` does not bring a validation provider, so `@NotNull` on a request-body record is ignored and a missing field arrives as `null` - which is worse than not annotating, because the annotation reads like a check. The controller validates the fields the use case cannot be handed at all, and the reason it is a check rather than a second failure vocabulary is that a malformed request is a `400` while a well-formed request the domain refuses is a `422` with a code. |



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
boundary rules. The modules with no code are the ones whose phase has not arrived: `apps/web` (Phase 1, and 0%),
`platform/security` and `platform/observability` (Phase 3), and `infra/*` beyond the compose stack.

This paragraph used to say that `apps/api` and `workers/settlement` "have no sources" and that those were Phase 1
work, which had been untrue for several slices - a stale claim in the document that exists to say what is true.


*End of master plan. Changes to this document follow section 12.*
