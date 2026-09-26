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

## How a refusal is classified

Section 4.3 of `docs/specs/settlement.md` requires every adapter to say whether a failure is **retryable**
(network, 429, 5xx) or **terminal** (insufficient funds, rejected), and to retry only the first kind. That
classification is in the port, as a sealed `RailRefusal` with exactly two shapes:

- **`RailUnreachable`** - the rail did not answer. Nothing was offered and nothing moved, so the same
  instruction may be sent again. This is what a network error, a 429 and a 5xx all become, and it is also what
  the breaker below answers with while a rail is being left alone.
- **`RailRejected`** - the rail answered that it will not take the instruction. Asking again would produce the
  same answer, so the caller must stop and treat the transfer as failed.

Two records rather than one flag, because a caller that has to switch over the type cannot forget the
difference; `retryable()` exists for the places where the classification is all that matters - a backoff
policy, a circuit breaker - and those read it rather than re-deriving it. Both carry a stable code
(`rails.unreachable`, `rails.rejected`) so a refusal that reaches a report can be alerted on without parsing
prose, and free-text `detail` for the rail's own words.

**A reply is still a reply.** `Result.ok` means the rail took the instruction; the state inside it may be
`confirmed`, `pending`, or `failed` (it accepted and then could not move it - a different thing from refusing
to take it, and the worker treats both as terminal for the transfer while only the refusal is attributed to
the instruction).

## Leaving a broken rail alone

`Rails.withCircuitBreaker(rail, policy, clock)` decorates any rail with a circuit breaker: after
`failuresBeforeOpening` consecutive *unanswered* calls the rail is not called at all for the cooldown, and then
exactly one call is allowed through as a probe. An answer of any kind - including a refusal - closes it, because
a rail with an opinion is a working rail; only silence counts.

- **A decorator, not a rail.** "Stop calling it" is a decision about the caller's behaviour, so it wraps
  whatever rail the configuration named and a future HTTP adapter inherits it.
- **In the port's own vocabulary.** While open, calls are answered with `RailUnreachable` and the cooldown in
  the detail. From a caller's side a rail it has stopped calling is indistinguishable from one that is not
  answering, and the consequences are identical - so no caller needs a branch for it.
- **In memory.** A breaker is a memory of a recent bad spell in *this* process's dealings with a rail; a restart
  is allowed to forget it, which is what lets an operator recover a deployment by restarting it.
- **The clock is passed in**, so a cooldown is testable without sleeping and this module never reads a clock of
  its own.

The backoff for one instruction that keeps going unanswered is *not* here: how long a caller waits before
offering the same transfer again is the caller's policy (`workers/settlement` has it), and the port's job stops
at saying which refusals could ever change.

**Tests:** `./gradlew :libs:rails:test` - 13 tests: what `MockRail` settles, confirms, refuses and remembers
(RULE S2 at the rail), and the breaker's arithmetic with a clock the test moves (opens after N, refuses without
calling, one probe per cooldown, closes on an answer, ignores terminal refusals, treats a thrown exception as
silence).

**Owner:** integrations reviewer.
