# libs/rails

**Purpose:** the `PaymentRail` port and its adapters. The seam between reductxn and the outside
world of money movement.

**Public surface:** `io.reductxn.rails.api.PaymentRail` plus its request/result types, and `Rails` -
the factory every implementation is reached through (RULE B10 keeps the implementations in
`internal/`, so nothing outside this module can name one).

**May depend on:** `libs/kernel`, `libs/ledger` (RULE B4).

**Must NOT depend on:** `libs/netting`, Spring (adapters may use an HTTP client, but the port must not).

**Adapters:**

- `MockRail` - **implemented**, deterministic, used by tests and demos. Confirms what it accepts, settles
  the currencies the catalogue seeds, refuses anything else terminally, and answers a re-submission with
  the state it already holds (RULE S2 at the rail). It is the default in `workers/settlement`
  (`reductxn.rail.name=mock`), which is the honest default for a project with no CBDC connection yet.
- `LedgerOnlyRail` - records intent, moves nothing. The default for record-only deployments. *Not
  implemented.*
- `SandboxRail` - CBDC sandbox integration. *Not implemented.*
- `PspRail` - a licensed PSP. *Not implemented.*

**Rule:** private keys are never stored. Signing happens on-device or in the PSP wallet.

## What the port does not express yet

Section 4.3 of `docs/specs/settlement.md` requires every adapter to classify failures as **retryable**
(network, 429, 5xx) or **terminal** (insufficient funds, rejected), and to retry only the first kind with
backoff and a circuit breaker. `submit` returns `Result<RailReference>`, which says *that* the rail refused
and has nowhere to put *whether* the refusal was retryable - so the classification is unimplemented and the
port needs a change before it can be. Its first caller fails safe meanwhile: a call that did not complete
leaves the transfer `authorized` in `workers/settlement` (nothing was refused and nothing moved) and the run
`executing`, and a later pass offers it again.

Section 4.4's reconciliation sweep is likewise absent: a `submitted` transfer is resolved by a later pass
polling `status`, which is the same logic on a caller's timetable rather than a job's.

**Tests:** `./gradlew :libs:rails:test` - 5 tests, all against `MockRail`, since it is the only
implementation and the only rail a deployment can move money through.

**Owner:** integrations reviewer.
