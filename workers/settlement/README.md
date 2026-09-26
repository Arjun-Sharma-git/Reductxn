# workers/settlement

**Purpose:** owns the settlement run: lifecycle, quorum, idempotency, rail submission, funding state. Runs the
netting solver via `libs/netting`, and the per-currency plan via `libs/planning`.

**Public surface:** none - it publishes events and writes its own tables. It has no HTTP surface at all, and
`spring.main.web-application-type=none` is set so that stays true: an endpoint here would be a second write path
to the same rows with none of the API's validation or error vocabulary.

**Owns tables** (RULE B17), versions `5xx`:
`settlement_cycles`, `settlement_runs`, `settlement_partitions`, `net_positions`, `settlement_transfers`,
`settlement_decisions`, `settlement_fx_fundings`.

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

**A run's whole lifecycle, and it executes.**

`ComputeRunService` opens a run for a `(group, as-of)` pair and records the plan it will execute: one partition
row per currency, one net position per participant per currency, and one transfer per movement with its rail
reference. The run then sits in `awaiting_approval`, or in `awaiting_fx_funding` when any currency's
`fx_clearing` position is non-zero (RULE S4) - a real blocking state rather than a warning.

From there:

- `DecideOnRunService` records a member's answer. `Quorum` decides what it means: a majority of the run's own
  participants, and every net debtor personally (RULE S1). A rejection ends the run; an approval only counts
  (ADR 0016).
- `RecordFxFundingService` records currency arriving from outside - reductxn cannot move it itself (P2) - and moves
  the run from `awaiting_fx_funding` to `awaiting_approval` once every currency the plan needs is covered.
- `ExecuteRunService` checks every gate before it moves anything, then walks the transfers: resolved ones are
  skipped, ones the rail already has are polled, and the rest are written `authorized` and offered to the rail.
  The run is `done` when every transfer is confirmed, `failed` when one was refused terminally with the confirmed
  ones left confirmed (RULE S3), and `executing` while the rail has not answered.

All eight lifecycle states are therefore reachable.

Nothing schedules any of it: the tests call the use cases, and a scheduler or an operator's command is a later
slice. And **nothing is posted to the ledger** when a run executes - the rail moves the money and the run records
it, but the obligations the cycle netted are still open. That is the next slice and it is a boundary question
(RULE B6, B17) rather than an omission. `docs/specs/settlement.md` section 8 has the detail, with a reason per
gap.

**The rail is a deployment choice, named rather than assumed.** `reductxn.rail.name` selects it (default `mock`,
reached through `libs/rails`'s factory because its implementations are internal - RULE B10), and an unknown name
fails at startup rather than falling back: a worker that moved money through a rail nobody configured is the
failure that line exists to prevent.

**Owned-table note:** the fourth and fifth tables arrived with the first run, and two more with quorum and
funding. `settlement_partitions` exists because the plan's per-currency figures - gross, net, tax, tax saved -
cannot be re-derived from the transfers: they are the netting engine's answers about the partition, and
re-deriving them would mean running the engine again to read a run. `settlement_decisions` is keyed by
`(run_id, member_id)`, which is what makes a decision final at the database and not only in the guard that checked
first. `settlement_fx_fundings` is keyed by the caller's `funding_ref`, which is the same idempotency rule (S2)
applied to the other thing in this module that can half-happen.

**Tests:** `./gradlew :workers:settlement:test` (Testcontainers, real Postgres; 47 tests across six classes). The
tests drive the inbound ports rather than the service classes, and they verify *stored* state: per-currency
zero-sum read back from the rows (N1), every participant zeroed by applying the plan's own transfers (N2), the
clearing account's residual, and - on the execution side - that the rail was asked exactly once per transfer and
not at all when a gate refused.

**Owner:** backend reviewer.

