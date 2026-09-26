# workers/settlement

**Purpose:** owns the settlement run: lifecycle, quorum, idempotency, rail submission, funding state. Runs the
netting solver via `libs/netting`, and the per-currency plan via `libs/planning`.

**Public surface:** none - it publishes events and writes its own tables. It has no HTTP surface at all, and
`spring.main.web-application-type=none` is set so that stays true: an endpoint here would be a second write path
to the same rows with none of the API's validation or error vocabulary.

**Owns tables** (RULE B17), versions `5xx`:
`settlement_cycles`, `settlement_runs`, `settlement_partitions`, `net_positions`, `settlement_transfers`.

**May depend on:** `libs/kernel`, `libs/ledger`, `libs/netting`, `libs/planning`, `libs/fx`, `libs/rails`,
`platform/*`.

**Must NOT contain:** business rules that belong in `libs/*`. Orchestration and persistence only - and the one
decision it does make, whether a run waits for approval or for funding, is a rule about the group's money rather
than about how a row is written.

**Must NOT depend on:** `apps/api` (RULE B6), in either direction. The API and the worker share tables through
read models and never code; `libs/planning` and `libs/booking` exist precisely so that the plan and the entries
can be computed on both sides without one calling the other. ArchUnit enforces both directions now that both
deployables have sources.

## What it does today

`ComputeRunService` opens a run for a `(group, as-of)` pair and records the plan it will execute: one partition
row per currency, one net position per participant per currency, and one transfer per movement with its
rail reference. The run then sits in `awaiting_approval`, or in `awaiting_fx_funding` when any currency's
`fx_clearing` position is non-zero (RULE S4) - a real blocking state rather than a warning.

Nothing schedules it, and nothing executes a run. There is no quorum, no rail adapter and no way for a
currency's requirement to become "funded", so `executing`, `done`, `rejected` and `failed` are in the
lifecycle's vocabulary and unreachable. `docs/specs/settlement.md` section 8 has the detail, with a reason
per gap.

**Owns table-list note:** the fourth and fifth tables arrived with the first run. `settlement_partitions` is the
plan's per-currency summary - gross, net, tax and tax saved - and it exists because those figures cannot be
re-derived from the transfers: they are the netting engine's answers about the partition, and re-deriving them
would mean running the engine again to read a run.

**Tests:** `./gradlew :workers:settlement:test` (Testcontainers, real Postgres). The tests drive the inbound port
rather than the service class, and they verify the *stored* plan: per-currency zero-sum read back from the rows
(N1), every participant zeroed by applying the plan's own transfers (N2), and the clearing account's residual,
which is what RULE S4 gates on.

**Owner:** backend reviewer.

