# ADR 0018 - The worker drives what it owns, and an operator triggers the rest

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`DriveRuns`, `DriveRunsService`, `Runs.dueForDriving`/`markDriven`,
  `V505`, `ScheduledRunDriver`, `SettlementCommand` and its runner, `@EnableScheduling`)
- **Related:** RULE S1-S4; ADR 0015, 0016, 0017; `docs/specs/settlement.md` sections 4 and 8

## Context

Everything worked and nothing happened. A run could be opened, computed, recorded, approved, funded - and then it
sat in `awaiting_approval`, `awaiting_fx_funding` or `executing` for ever, because the only caller of
`ExecuteRun` in the repository was a test. The plan's honest position called it "nothing triggers any of it", and
the sharper version of the same fact turned out to be worse: **the worker as a service did not stay up at all.**
A non-web Spring Boot application whose context has no non-daemon thread exits when `main` returns, so
`java -jar workers-settlement.jar` refreshed a context, ran its runners and stopped. There was no deployment in
which this worker did anything between test runs.

Two questions followed, and they are different:

1. **What moves a run nobody is asking about?** The last approval, the currency arriving, a rail answering a poll -
   all facts that arrive somewhere this module cannot observe, and each of which should let a run proceed.
2. **Who triggers what only a person can decide?** Opening a cycle needs a *when* (a group's settlement
   schedule), and funding needs somebody who watched currency arrive. Neither is a fact this worker can discover.

## Decision

**1. The worker owns its own triggers, because it owns the work.** A cadence in this deployable - `@Scheduled`,
on by default - asks "which runs can progress?" and offers each one to `ExecuteRun`. The driver has no rules of
its own: quorum, funding, rejection and rail support are all checked inside the use case, so the driver decides
*which* runs to offer and never *whether* one may move. A driver with its own rules would be a second place where
"may this run execute?" is answered, and the two would eventually disagree about money.

**2. The cadence and its window are operational settings, not policy.** Nothing about a settlement says how often
to look at it; how often to look costs rail calls, so it is configuration with documented defaults
(`PT1M` / `PT2M` / `50`). The window is a lease on attempts, and it is the reason `V505` exists: a run is due when
its `driven_at` is older than the cut-off, and the mark is written **before** the attempt, as every other write
here is (section 4.2). Two instances may still both drive one run - that is safe rather than designed away,
because the rail is idempotent on a reference, a resolved transfer is never offered again and a duplicate ledger
entry is a no-op - and what the window prevents is not corruption but a stampede.

**3. The due query takes the least recently attempted first, and that is the anti-starvation mechanism.** A run a
gate keeps refusing is attempted once per window and then goes to the back of the queue, so a group that never
approves its run cannot keep a run that is ready to settle from ever being looked at. A batch size bounds one
pass, so a backlog drains over several minutes instead of holding one tick for an hour.

**4. A refusal is a report line, not an error.** Most of what a pass finds is a run waiting on a group or on
currency. The gates answer with a coded refusal and the report says which gate is waiting: "one settled, one is
unfunded in JPY, one is waiting on approval" is the operator's whole view of a worker doing its job quietly,
where "three runs considered" would make a stuck run look like a working one.

**5. What is not automatic gets a command line, and the command line does not replace a person's decision.**
`--reductxn.command=compute` opens a cycle (the *when* is the operator's, because group schedules do not exist),
`drive` does one pass, `fund` records currency arriving under a reference the treasury knows. **Approving a run is
deliberately not a command**: `DecideOnRun` is a member's act, and an operator who could approve would settle a
group's money with a keystroke no member made. Until members exist and a member surface can carry their answers,
approvals are recorded through the port - which is what the tests do, and what this ADR names as the next decision
rather than papering over.

**6. No new surface between the deployables.** The worker still has no HTTP surface, `apps/api` still does not know
the worker exists, and the two share tables rather than calls (RULE B6). A command line is an operator with a
shell; it is not a network surface, and it is not a second write path from another module.

## Consequences

- **The worker is a long-running process now.** The scheduled thread is non-daemon, which is what keeps the JVM
  alive; that is a side effect of the decision above, and it is the reason the deployment exists at all.
- **A restart resumes what a crash left.** A run left `executing` by a dead instance is in the due query's states,
  so the next process finishes it - the reconciliation property falls out of driving rather than needing a
  separate recovery job.
- **Every run carries a "last attempted" fact**, which is the first thing to look at when a run appears stuck. No
  per-transfer timestamp was needed: `ExecuteRun` already decides per transfer whether to submit or poll, so the
  unit the threshold belongs to is the run - which corrects `V503`'s note about a `submitted_at` column, with the
  reason recorded in `V505`.
- **Every pass is bounded and ordered**, so a growing backlog shows up in the log line's counts rather than hiding
  behind a slow tick.
- **There are two honest ways to run this worker**: as a daemon (default), or as a scheduled job with the cadence
  off and `--reductxn.command=drive`. Running both would double the rail traffic, which is why the switch exists
  rather than being assumed.

## What becomes harder, and what is still missing

- **Nothing decides when a cycle is due.** A group's settlement schedule is a group's property and groups do not
  exist, so cycles are opened by an operator or not at all. A slice that invented "settle whatever is open, every
  tick" would be deciding about a group's money on the group's behalf.
- **A member's approval has no channel to this worker.** This is the largest remaining gap in the product: a run
  cannot reach `executing` without an approval recorded through a port call. It needs identity (who is a member),
  a member-facing surface (the API), and a way for that surface to reach this module - which is its own ADR, and
  the reason it is not improvised here.
- **A rail that never answers is polled for ever.** The driver resolves what a rail has decided; nothing escalates
  a transfer that no rail will decide. Section 4.4's "or escalate" is still unimplemented, and the driver's report
  is what would feed it.
- **Retry classification is still absent** (`PaymentRail` cannot say whether a refusal was retryable), so a
  retryable rail failure is retried by the next pass rather than with backoff and a circuit breaker. The cadence
  makes that retry happen, which is an improvement rather than the feature.

## Alternatives considered - and why rejected

**An endpoint on the worker, called by `apps/api` when a member approves.** It would close the approval gap in a
day, and it buys three problems: the worker would grow the HTTP surface its own documentation forbids, the API
would learn that this deployable exists (a runtime coupling the current design does not have), and there would be
two write paths to the same run rows - the API's validation and error vocabulary on one side, none on the other.

**`apps/api` writing the worker's tables directly** (the reverse read model, made a write model). No new surface,
and it puts the quorum rule, the "is the run open" rule and the funding arithmetic in the API - a second
implementation of what a run is, which is exactly what the module graph's rules exist to prevent.

**External scheduling only: a cron job runs a command and the worker has no cadence.** Simpler in one way (no
in-process timer) and worse in another: the schedule then lives somewhere no test can see, and a worker that is
only ever a command never notices that a rail answered forty seconds ago. It is supported as a mode
(`drive.enabled=false` plus the command) rather than being the design.

**Driving every non-terminal run, unbounded, in one pass.** Two failures: a backlog of a thousand runs would hold
one tick for an hour of rail calls, and ordering by creation would let a group that never approves starve a run
that is ready.

