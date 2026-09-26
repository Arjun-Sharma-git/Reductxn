# ADR 0019 - A rail's refusal says whether asking again could help

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `libs/rails` (`PaymentRail.RailRefusal`, `RailUnreachable`, `RailRejected`, `BreakerPolicy`,
  `Rails.withCircuitBreaker`), `workers/settlement` (`V506`, `Backoff`, `ExecuteRunService.refuse`, config)
- **Related:** RULE S1-S4; ADR 0001, 0018; `docs/specs/settlement.md` section 4

## Context

Section 4.3 of the settlement spec asks for three things: failures classified as retryable or terminal, only the
retryable ones retried, and retries with exponential backoff behind a circuit breaker. The port's own javadoc
claimed the first of those - "the rail's reference, or a failure classified as retryable or terminal" - and its
signature could not express it: `submit` returned `Result<RailReference>`, `Result.fail` meant only *that*
something went wrong, and the one refusal type in the repository (`RailUnreachable`) lived in a **worker's test
sources**, where no other module could see it and where the architecture test that checks error-code conventions
could not reach it either.

In practice the worker inferred the classification: `Result.fail` was treated as retryable ("the call did not
complete, leave the transfer `authorized`") and `Result.ok` carrying a `failed` status as terminal. That
inference is wrong for any adapter that is not the mock - an HTTP adapter answers a 400 with a failure, and a 400
re-sent is a 400 - and it puts a decision about the rail's semantics in the caller, where the next adapter would
have to reproduce it exactly.

There was also nothing to stop a worker from hammering a rail that was down: every pass offered every authorized
transfer, so a rail having a bad minute collected one call per transfer per minute for as long as it lasted.

## Decision

**1. The classification is in the type, as a sealed pair.** `RailRefusal` has exactly two implementations:
`RailUnreachable` (nothing was offered, nothing moved, the same instruction may be sent again) and
`RailRejected` (the rail answered that it will not take it, so asking again changes nothing). Both are
`DomainError`s with stable codes, so a refusal that reaches a report can be alerted on without parsing prose. A
single record with a `boolean retryable` flag would have been easier to construct and easier to get wrong; two
records mean a caller that switches over the type cannot forget the difference, and the `retryable()` method is
there for the places where the classification is all that matters.

**2. Retryability is the rail's to state, not the caller's to infer.** The adapter knows whether it got a 429 or
a 400; the worker reads `retryable()` and does not interpret messages. An unclassified `DomainError` from an
adapter is treated as retryable, and that is the safe direction: nothing has been explained, so failing a run on
it would report money as not moving for a reason nobody can act on.

**3. A terminal refusal fails the transfer, and S3 follows from it.** Before this, a rail that *answered* "no"
through `Result.fail` would have left the transfer offered for ever. Now the transfer is `failed`, the run is
`failed (partial)` with the confirmed transfers left confirmed, and the group sees which movement did not happen.
This is the behaviour change the classification buys, and it is the difference between a retry loop and a
settlement that reports itself as incomplete.

**4. A refused poll is read as "we still do not know".** The classification answers *"could asking again help?"*,
and for a poll the answer is always yes: polling has no side effects, so a terminal reading would turn a rail's
refusal to answer a question into money marked as not having moved. The cost is a reference a rail refuses to
answer being polled until the cap, which is the escalation gap and is recorded as one rather than guessed at.

**5. The wait between attempts is the caller's policy, and it lives on the transfer.** `V506` adds
`unanswered_attempts` and `next_attempt_at` to `settlement_transfers`, and `Backoff` is a pure record - base,
cap, and a count in, an instant out. The count is *consecutive calls that went unanswered*: any answer, including
a refusal, resets it, so a rail that is working is never made to wait for failures it has recovered from. A
transfer whose wait has not expired is skipped, including by an operator's manual drive, because the wait is the
rail's health rather than a stampede guard.

**6. The breaker is a decorator over any rail, in memory, and speaks the port's vocabulary.**
`Rails.withCircuitBreaker(rail, policy, clock)` wraps whatever rail the configuration named; after N consecutive
*unanswered* calls it stops calling for the cooldown, then allows exactly one probe; an answer of any kind closes
it. While open it answers `RailUnreachable` with the cooldown in the detail, so no caller needs a branch for "and
also the breaker is open" - a rail we have stopped calling and a rail that is not answering have the same
consequences. The state is in memory: a breaker is a memory of a recent bad spell in *this* process, and a
restart is allowed to forget it.

**7. The due query asks whether there is anything attemptable.** A run whose every unresolved transfer is waiting
out a backoff is not due, so a pass does not mark it as attempted while nothing happened. That makes
`Runs.dueForDriving` take two instants - one for the run's lease, one for the transfers' readiness - which is two
questions rather than an awkward signature.

## Consequences

- **A rail with a long outage stops receiving traffic**, and the transfer's `rail_detail` says so in the port's own
  words, so "quiet rail" and "broken rail" are distinguishable from the row.
- **A refusal that will not change is reported instead of retried**, so a group's insufficient funds surface as a
  failed run rather than as a transfer that is offered every minute for ever.
- **The two mechanisms compose.** A breaker-tripped call counts as an unanswered attempt, so the transfer backs off
  while the rail is left alone - and when the rail recovers, the probe that closes the breaker is the same call
  that moves the transfer.
- **Time-dependent behaviour is testable.** Passing a clock into the breaker, plus the clock bean the use cases
  already took, means the tests move time instead of sleeping: `BackoffTest` asserts the doubling exactly, and
  `BrokenRailTest` walks a whole cooldown in milliseconds.
- **`libs/rails` declares error codes**, so the architecture test that enumerates the modules declaring codes
  changed - and said so loudly rather than going blind, which is exactly what that assertion is for.

## What is still missing

- **Escalation, which is now the only half of section 4 open.** A rail that never answers is retried with backoff
  until the cap and then polled for ever; nothing decides when patience has run out, and nothing tells a human.
  The material for it exists (`unanswered_attempts`, `next_attempt_at`, `rail_detail`), which is deliberate: the
  mechanism is in place and the policy is not invented here.
- **A shared breaker across a fleet.** Each instance keeps its own counters, so two instances will each call a
  failing rail up to N times before stopping. Sharing it needs a shared clock and threshold, which is a different
  design rather than a column.
- **No jitter in the backoff.** The transfers in one run fail together and therefore retry together. The cap bounds
  the burst and the breaker stops it outright, so jitter would be sophistication rather than protection - but it is
  a real trade-off, written down rather than overlooked.

## Alternatives considered - and why rejected

**One record with a `retryable` flag.** Less code and one fewer type; and a caller that reads the flag without
switching on the case can silently treat "refused" and "unreachable" as one thing - which is the mistake this ADR
exists to prevent.

**Inferring retryability from the failure's code or message.** What the worker did before, and it works only for
the adapter that produced the messages: it makes every future adapter's vocabulary a contract nobody wrote down.

**Backoff in `libs/rails`, beside the breaker.** Tempting symmetry, and wrong: the port's job is to say whether a
refusal could change, and how long a *caller* waits before trying again is the caller's policy - a different
settlement engine could reasonably choose a different one.

**A breaker with an exponential cooldown.** It looks more capable and is a second backoff wearing a breaker's name:
the per-transfer wait already handles one instruction failing repeatedly, and this handles the rail being down.
One probe per cooldown answers the second question.

**Persisting the breaker's state so both instances share it.** It makes one process's health a shared fact, and two
instances failing together would then silence a rail for both - an outage amplifier rather than protection.

