# reductxn

**A non-custodial group commitment ledger with tax-optimised CBDC settlement.**

Members record intended transfers as obligations. Nothing moves. At cycle end, reductxn nets
everyone's positions and reduces the group's O(N^2) obligations to at most N-1 real CBDC
transfers - the minimum needed to settle the group, which also minimises the per-transfer
CBDC tax.

> **Read [`docs/MASTER-PLAN.md`](docs/MASTER-PLAN.md) first.** It is the single source of truth
> for the architecture, the module graph, and the RULEs that keep the code modular.

## Status

Pre-implementation. Phase 0 - decisions and ADRs.

## Stack

| Layer | Choice |
|---|---|
| Backend | Spring Boot 4.1.x - Java 25 LTS - Gradle 9 multi-project |
| Persistence | PostgreSQL 16 - jOOQ - Flyway |
| Async | Kafka / Redpanda - transactional outbox - Spring Batch |
| Frontend | React 19 - Vite - TanStack Router/Query/Form/Table - GSAP |
| Contracts | OpenAPI 3.1 - `contracts/openapi/reductxn-api.yaml` is the source of truth |

## Layout

| Path | Role |
|---|---|
| `libs/` | Pure Java domain core. No Spring, no JDBC, no HTTP. |
| `apps/` | Deployables: `api` (Spring Boot), `web` (React). |
| `workers/` | Batch and stream processors. |
| `platform/` | Cross-cutting infrastructure configuration (non-domain). |
| `contracts/` | OpenAPI and event schemas. |
| `infra/` | Docker, Kubernetes, load tests, CI. |

## Prerequisites

- Java 25 LTS - `sdk install java 25.0.4-tem`
- Node 24 (`pnpm` for the web app)
- Docker (Postgres, Redis, Redpanda, MinIO)

## Quick start

**From a new machine, read [`QUICKSTART.md`](QUICKSTART.md).** It is the whole path - the database, the two backend
deployables, the web client, the first five minutes in the product, and the five ways a fresh checkout fails - and
every command in it was run against an empty database before it was written.

The short version, once the database is up (`docker compose -f infra/docker/compose.yaml up -d postgres pgbouncer`)
and `REDUCTXN_TAX_JURISDICTION` is set:

```bash
./gradlew :apps:api:bootRun             # HTTP on :8080
./gradlew :workers:settlement:bootRun   # the settlement cadences; run both, see QUICKSTART section 3
pnpm -C apps/web dev                    # http://localhost:5173
```

The gates:

```bash
./gradlew build            # backend + RULE enforcement (ArchUnit)
pnpm -C apps/web check     # types, lint, catalogs, unit tests, a build
```

The same three commands a developer runs are what `.github/workflows/ci.yml` runs, in two jobs: `api` is
`./gradlew clean build` (Temurin 25, on a runner with the Docker the Postgres-backed integration tests
need) and `web` is `pnpm install --frozen-lockfile`, `pnpm check` and `pnpm e2e` (Node 24, Chromium
installed by Playwright). The pipeline adds two checks a local run cannot make: it regenerates the
OpenAPI client and the route tree and fails if the committed artefacts differ from what the sources
produce (RULE C3), and it starts from an environment nobody has been warming up - which is where the
last two verification defects were found. What it deliberately does not add is a second definition of
"passing": no CI-only flags, no test selection. See ADR 0031.

> **Note:** the `gradlew` wrapper is committed, jar included, so a fresh clone can run `./gradlew` with no local
> Gradle. (This note used to say the opposite - that the wrapper JAR was "intentionally not committed" and had to be
> generated once with `gradle wrapper --gradle-version 9`. That stopped being true at some point and nobody
> corrected it; `git ls-files gradle/wrapper` lists the jar. A new machine following the old note would have
> installed Gradle for nothing.)
