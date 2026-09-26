# platform/persistence

Data access wiring shared by deployables: the DataSource and its pool, jOOQ, and Flyway execution.

**Public surface:** Spring configuration only. No API for callers to program against - deployables get
persistence by depending on this module and setting properties.

**May depend on:** `libs/kernel` and infrastructure libraries (jOOQ, Flyway, the JDBC driver, HikariCP).

**Must NOT depend on:** `apps/*`, `workers/*`, or any other `libs/*`.

**Must NOT contain:** domain concepts, and specifically **no table definitions**. Schema ownership belongs to
the module that owns the table (RULE B17):

| Tables | Owner | Migrations live in |
|---|---|---|
| `obligations`, and `groups`/`users`/`kyc_*` when they exist | `apps/api` | `apps/api/src/main/resources/db/migration`, versions `1xx` |
| `tax_rules`, `tax_assessments` | `libs/netting` | `libs/netting/src/main/resources/db/migration`, versions `2xx` |
| `ledger_accounts`, `journal_entries`, `postings` | `libs/ledger` | `libs/ledger/src/main/resources/db/migration`, versions `3xx` |
| `settlement_cycles`, `settlement_runs`, `settlement_partitions`, `net_positions`, `settlement_transfers` | `workers/settlement` | `workers/settlement/src/main/resources/db/migration`, versions `5xx` |
| `currencies`, `fx_rates`, `fx_quotes` | `libs/fx` | `libs/fx/src/main/resources/db/migration`, versions `4xx` |

This module only runs them.

## The three decisions in here

**Flyway runs against Postgres directly, not through PgBouncer.** DDL on a transaction-pooled connection is
the one thing the pooler cannot be relied on to carry, so `spring.flyway.url` points at the database while
`spring.datasource.url` points at the pooler. That also means Flyway's credentials are separate:
setting `spring.flyway.url` stops it reusing the DataSource, and it does **not** inherit the username and
password with it. The symptom is a server answer about SCRAM authentication and no password.

**Prepared statements are client-side.** `prepareThreshold=0` and `reWriteBatchedInserts=true` are set on the
DataSource, because transaction pooling returns a connection to the pool between transactions and anything
server-side and session-scoped is gone by the time the next transaction picks it up. It is configured in
Java rather than in a JDBC URL so it cannot be dropped by one of the environment-specific pieces a URL is
assembled from.

**Zero migrations is a refusal, not a no-op.** `MigrationsConfiguration` counts what Flyway found and stops
the process if the answer is none. It is not a formality: migrations live beside their owning module, so the
set is assembled from several jars, and a module whose resources are not where Flyway looks contributes
nothing while looking perfectly healthy. The alternative is a successful start behind an empty schema and a
first query that fails with something about permissions.

## What is not here yet

Connection pooling under load, read replicas, and the PgBouncer path itself beyond the local compose stack.
The integration tests run against a container with no pooler in front of it, which is the honest limit of
what they prove: they cover the migrations and the JDBC settings, not the proxy.

**Tests:** `./gradlew :platform:persistence:test` (Testcontainers, real Postgres).

**Owner:** platform reviewer.
