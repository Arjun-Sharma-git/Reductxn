# ADR 0050 - A request to execute travels as a row, and the worker spends it

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `POST /runs/{runId}/execute` in `RunsController`, `RequestRunExecution` and its use case, the
  `RunExecutionRequests` out-port and its jOOQ adapter, `RunRefusals.NotReadyToExecute`, the contract (a description,
  `401`/`404`/`409`, a receipt body for the `202`, and no `Idempotency-Key`), `RequestExecutionApiTest`; on the worker's
  side `V512__execute_requests.sql`, the due query's third disjunct, the spend in `markDriven`, and two tests in
  `DriveRunsTest`
- **Aligns with:** ADR 0020 - a member's surface is here and the run is there, and the two deployables do not depend on
  each other
- **Related:** ADR 0046 (which left this operation standing), ADR 0032 (a cycle is a claim, the worker computes it),
  ADR 0021 and 0023 (why the API projects a verdict rather than deriving it), ADR 0049 (a member is who may write to a
  group's books), ADR 0008 (the rail); RULE B6, B15, B20, B21, C1, C3, S1-S4

## Context

ADR 0046 served the quote and left two operations standing, with a paragraph that named their owners and, more
importantly, **two reasons**:

> What `execute` needs before it can start is its own ADR: a run-state licence, a claim so two workers cannot execute
> one plan, and per-transfer outcomes - and **the rail adapter it names does not exist in this repository, which is a
> fact rather than a scheduling decision.**

By the time this slice started, every part of that paragraph had become false, and none of it had been revisited:

- **The rail adapter exists.** `libs/rails` has `Rails`, `PaymentRail`, `MockRail`, `GuardedRail` and a circuit
  breaker, with tests.
- **The run-state licence, the claim and the per-transfer outcomes exist.** `ExecuteRun` is implemented in
  `workers/settlement` with `ExecuteRunTest` (11 tests: nothing reaches the rail without quorum or funding, a replay
  asks the rail for nothing new, a refused transfer fails the run while the confirmed ones stay confirmed, a rail that
  cannot be reached leaves the transfer `authorized`), and the per-transfer state is `V503`'s.
- **"Leaves money in a state nothing can resolve" is false too.** That was the argument for building `execute` and the
  webhook *together*, and the polling driver - which asks a rail about what it has not answered - resolves a silent rail
  with no callback at all. The webhook is now an improvement in latency rather than the only way out.

And the same finding recurred one level down, where it is easy to miss. `DriveRuns.driveOne` is the primitive for
"drive this named run, ignoring the window", and **nothing in production called it** - only tests did. This repository
has the lesson written down already for `ExecuteRun` itself:

> A service nothing calls is indistinguishable from a service that works ... a test *is* a caller, so `ExecuteRun` had
> twenty assertions and no production caller, so "settlement executes" was true of the code and false of the product.

The schedule answered that for `ExecuteRun`. Nothing answered it for the primitive that ignores the window, because a
cadence has no reason to want one: it drives what is due, and "ignore the backoff" is exactly what a *person* wants and
a *pass* never does.

## Decision

**`POST /runs/{runId}/execute` is a request, not an execution, and it travels the way every member intent travels here:
as a row the API writes and the worker reads.**

- **The API records the request on the run itself** - `settlement_runs.execute_requested_at` and
  `execute_requested_by`, added by the worker's `V512`. The API already writes another module's table for exactly this
  reason (a cycle's "close the books at this instant", ADR 0020), and calling the worker's use case instead is what RULE
  B6 forbids.
- **The driver honours it inside the window.** The due query gains one disjunct: a run whose request is later than its
  own last attempt is due however recently it was attempted. That is the whole mechanism - the gates, the lifecycle and
  the rail submission are untouched, because they were already right.
- **The attempt spends the flag, and the attribution stays.** `markDriven` clears `execute_requested_at` as it marks the
  attempt. A request that outlived its attempt would make the run permanently due and defeat `V506`'s backoff forever for
  that run. `execute_requested_by` is not cleared: the flag is pending intent, and who asked is a fact about the run's
  history - the same split as `rail_detail` beside a resolved transfer.
- **The `409` is projected, never recomputed.** "Quorum not reached, or already executing" is read from the run's status
  and from the verdict the worker wrote into `settlement_run_gates` (ADR 0023). Counting a quorum here would be a second
  implementation of RULE S1 (ADR 0021), and `RunReport.Gates` exists so that this module never has to.
- **A run waiting on currency is accepted rather than refused.** The contract's `409` names quorum and "already
  executing"; the driver honours the request the moment the currency arrives, so recording the intent is worth more than
  refusing it. This is a decision, and there is a test whose only job is to fail if somebody widens the refusal.
- **The caller is a member of the run's group** (ADR 0049), refused with the one code for "no such run" and "not yours".
## Consequences

- **The recorded gaps are down to one, and its *reason* changed rather than the entry vanishing.** ADR 0046 left two
  operations standing together; this slice serves one of them, so the list is `POST /webhooks/rails/{provider}` alone -
  and the reason recorded against it is no longer "money cannot be resolved without it", which the polling driver made
  false. What is left is real but smaller: a callback is faster than a poll, and it needs a secret to verify a signature
  against and a web-capable place to receive it, neither of which exists. **The generalisable part is that a recorded
  gap carries a claim about the repository, and this repository changes** - so the entry now says what would make it
  false, which is the only form of it that does not rot quietly.
- **"A service nothing calls" was found a third time, and this time by asking.** MASTER-PLAN records the lesson from
  `ExecuteRun`: twenty assertions and no production caller, and a test is a caller, so a covered use case can still be
  unreachable. It recurred here one level down - `DriveRuns.driveOne`, whose whole purpose is the case a cadence never
  has. What made it findable was asking the question directly rather than reading test coverage: *who calls this in
  production?* Two of the three instances in this repository were found by accident.
- **Prose counts drifted again, and were fixed again - in the rows that named this operation.** The plan's coverage row
  said the gap list was "two entries long", and the contract's `bearerAuth` description still said "six boundaries"
  while listing a set that ADR 0048, 0049 and this slice had each changed. Both now state the rule rather than a number
  where a number would rot, which is the second time this lesson has been written down (ADR 0046) and the reason the
  third time should not need to be.
- **What this deliberately does not do.** Execution is still asynchronous: the driver's cadence is the engine, and this
  endpoint only lets a member move one run to the front of it. There is no cancel, no schedule and no retry policy of its
  own - a request that a gate refuses is a request the caller can make again once the gate is satisfied, which is what
  the `409` tells them. And `POST /runs/{runId}/decisions` remained the only write in the contract that took its actor
  from the body, deliberately, for the reason ADR 0016 gives: a stranger's answer is inert by the quorum's arithmetic.
  *(ADR 0053 served it, and found the reason half true - inert to the quorum, and not inert as a row that the group's
  activity feed shows.)*
