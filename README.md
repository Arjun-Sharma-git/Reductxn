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

```bash
./gradlew build            # backend + RULE enforcement (ArchUnit)
pnpm -C apps/web test      # frontend unit tests
```

> **Note:** the `gradlew` wrapper must be generated once on a machine that has Gradle
> (`gradle wrapper --gradle-version 9`). The wrapper JAR is binary and is intentionally not
> committed by this scaffolding.
