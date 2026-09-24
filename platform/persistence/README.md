# platform/persistence

**Purpose:** data access wiring shared by deployables: DataSource configuration, pool sizing,
PgBouncer-compatible settings, jOOQ configuration and Flyway execution.

**Public surface:** Spring configuration and jOOQ setup only.

**May depend on:** `libs/kernel` and infrastructure libraries (jOOQ, Flyway, the JDBC driver).

**Must NOT depend on:** `apps/*`, `workers/*`, or any other `libs/*`.

**Must NOT contain:** domain concepts, and specifically **no table definitions**. Schema ownership
belongs to the module that owns the table (RULE B17):

| Tables | Owner |
|---|---|
| `users`, `devices`, `sessions`, `kyc_*` | `apps/api` - identity |
| `groups`, `group_members`, `invites` | `apps/api` - group |
| `ledger_accounts`, `journal_entries`, `postings` | `libs/ledger` |
| `obligations` | `apps/api` - obligation |
| `settlement_*`, `net_positions` | `workers/settlement` |
| `tax_rules`, `tax_assessments` | `libs/netting` |

Flyway migrations live beside their owning module, not here. This module only runs them.

**Connection management is the first thing to break at scale,** which is why PgBouncer is in the
local compose stack (`infra/docker/compose.yaml`) so developers feel the constraint early.

**Owner:** platform reviewer.
