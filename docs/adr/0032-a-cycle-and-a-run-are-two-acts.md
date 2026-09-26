# ADR 0032 - A cycle and a run are two acts, and the second one happens without a person

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`Runs.openCycle`, `Runs.cyclesWithoutRuns`, `OpenCycle`, `ComputeOpenedCycles`,
  `ComputeOpenedCyclesService`, `ScheduledRunComputer`, `SettlementCommand`'s `open` and `compute-opened`),
  `Runs.describe`'s no-run branch, the trigger rows of `docs/specs/settlement.md`
- **Closes:** the plan's `Nothing schedules a cycle` and the cycle half of `A scheduler, or any other trigger` - as
  far as it can be closed without inventing a policy
- **Related:** ADR 0018 (the worker drives what it owns), ADR 0020 (the API records, the run judges), ADR 0021 (a
  read model does not recompute judgements), ADR 0030 (a run has to be findable); RULE B6, B17, S1, S4

## Context

**The gap was not that nobody could say "settle this".** The operator's command line could, and had since it existed:
`--reductxn.command=compute --reductxn.group=<uuid> [--reductxn.as-of=<instant>]` opens a cycle and computes the plan
over it in one transaction. What the repository recorded, in the spec's own words, was narrower and more interesting:

> Nothing schedules a *cycle* ... A run is driven once it exists, but nothing decides that a cycle has come due.

Two things were missing, and the plan's honest position kept them separate: **a policy** (when is a cycle due? - a
group's property, and `groups` does not exist, so it stays open) and **a mechanism** (a cycle that has come due gets
computed by something that is not a person). This ADR is about the second.

The mechanism could not exist, and the reason was structural rather than a matter of writing a scheduler:

- **Opening a cycle and computing its plan were the same act.** `ComputeRun.compute` derives the cycle's id, inserts
  the cycle, plans from the obligations open at that instant, and writes the run - all in one transaction. There was
  therefore never a cycle waiting to be computed. A pass looking for "opened, not computed" would have found an empty
  set for ever, and an operator who has to compute in order to open cannot close a period and settle it later.
- **The instant comes before the plan, not after it.** A cycle is a claim about a moment: everything recorded at or
  before `asOf` is in it, and the plan is a consequence of that choice. The one thing a settlement policy decides is
  that instant. A design in which the instant can only be chosen by the call that plans makes the policy impossible
  to express, whatever else is built.
- **`V501` had already allowed the separation.** `settlement_cycles` is keyed by `(group_id, as_of)` with a unique
  constraint whose own comment says it exists so that "a second attempt collides here instead of producing a second
  plan", and `settlement_runs` references the cycle with `one_run_per_cycle`. The schema was built for two acts; the
  code did one.
- **One invariant had to be given up, and it was load-bearing.** `JooqRuns.describe` threw on a cycle with no run over
  it - *"The two are written together, so this means a run row was deleted rather than that a cycle was opened
  without one"* - and `ComputeRun`'s retry path depends on `findRun` answering empty for a cycle that has no plan.
## Decision

**1. Opening a cycle is its own use case, and it writes one row.** `OpenCycle.open(RunRequest)` derives the cycle's
identity and inserts the cycle; nothing else. `RunRequest` is reused rather than given a twin, because it is the same
pair of values and the same key - which is exactly the property that makes an opened cycle computable later by the
existing path.

**2. The identity derivation stays in one place.** `OpenCycleService` calls `ComputeRunService.cycleIdOf` - the same
package-private static method the compute path uses, and this is why the two services share a package. A copied id
scheme would not fail to compile and would not fail a constraint; it would produce a cycle that nothing can compute,
discovered a cadence later. The unique constraint on `(group_id, as_of)` remains the arbiter of a race between the two
paths.

**3. Opening is idempotent, and the answer says which call did the work.** A second `open` for the same pair inserts
nothing and returns `false` - not an error, because the cycle exists, which is what the caller asked for. A shell
script that opens a cycle before settling it must be safe to run twice, and a policy that fires twice must not be able
to produce two plans for one cycle.

**4. Computing what was opened is a separate pass, on its own cadence.** `ComputeOpenedCycles.computeOpened(batchSize)`
reads the cycles that have no run and hands each one to `ComputeRun` - and it decides nothing else: which obligations,
which rates, which status, and the refusal when there is nothing to settle all stay in that use case, so there is one
answer to "what does this cycle settle". It is driven by `ScheduledRunComputer`
(`reductxn.settlement.compute.*`, enabled by default) and by `--reductxn.command=compute-opened`, because a deployment
may run this worker as a daemon or as a scheduled job, and only a command can be the second.

**5. The two cadences are two classes, because they are bounded by different things.** Driving is bounded by rail
calls; computing is bounded by the planner over one group's book. One tick doing both would tie the rate of rail
traffic to the rate of planning. The computer's idle cost is one indexed query, which is why its interval can be a
minute - and a minute is the staleness a member can watch, because they asked for a settlement and they are looking at
a list.

**6. Oldest first, because these are requests.** The pass serves a queue in the order it arrived. The alternative was
considered and rejected: newest-first would serve whoever just asked and leave the member who has been waiting longest
for last. `dueForDriving` can afford least-recently-attempted-first because a run carries `driven_at` and a run that
cannot progress is sent to the back of its queue; a cycle carries no such mark - which is the next point.

**7. The missing attempt marker is named rather than worked around.** Nothing records that a pass looked at a cycle, so
a cycle that cannot be computed (a group with nothing to settle at that instant) is returned on every pass until
something changes. That is not a bug to be ordered away, because "cannot be computed" is not permanent: an obligation
recorded later can carry an instant inside a cycle opened a month ago, which is the ordinary shape of a backdated bill,
and a pass that dropped the cycle would lose a real settlement. What bounds the cost is the batch size (one cheap
query and one refusal per such cycle) and the log, which names them. The fix is a column recording an attempt, and that
is a migration rather than a different `order by`. There is no window for the same reason: a window would stop looking
at exactly the old cycles a backdated commitment can still belong to.

**8. A refusal is reported, a fault is contained, and the difference is written down.** A cycle whose group has nothing
to settle is refused by the use case and reported as `refused` - an ordinary state, not an alert, and not an exit code.
A cycle whose plan could not be computed at all is caught per cycle, named as `faulted`, and the batch continues. That
is deliberately unlike `DriveRunsService`, which lets a fault out: driving calls `ExecuteRun`, whose failures are
refusals by design and whose writes are one transfer at a time, while computing calls the planner over a group's whole
book. Letting a fault propagate would stop the pass behind it for ever, because the cycle stays in the query - so one
group's broken book would become every group's.

**9. Each cycle is its own transaction, and the pass is not wrapped in one.** `compute` is `@Transactional` (a half
plan is worse than none) and the pass is not, so a fault on the fifth cycle leaves the first four plans committed.
`ComputeOpenedCyclesFaultTest` is what keeps that true: it registers a planner that throws for one group and asserts
that the plan the other group produced is still there.

**10. What this deliberately does not do: decide that a cycle has come due.** No interval-based "settle whatever is
open every time the job runs" exists here, because that is a decision about a group's money that this slice is in no
position to make. The spec's open question stays open, and none of its three answers is foreclosed by what was built: a
group's schedule can open cycles (it would call `OpenCycle`), a member can ask through the API (which will record a
request the worker consumes), or an operator can keep opening them by hand.

  Opening a cycle without computing it makes "no run yet" an ordinary state, so the throw became
  `Optional.empty()`. The first run of the new tests found this: seven tests failed in the adapter, none in the new
  code.

## Consequences

- **A settlement can now be started by a policy, or by a person, without a person computing it.** An operator opens a
  cycle (`--reductxn.command=open --reductxn.group --reductxn.as-of`), and within a minute the worker has computed the
  plan, decided whether the group approves it or funds it first, and put it where a member can find it. The two acts
  being separate is what makes a period closable today and settleable tomorrow, and it is what gives the *next* slice
  something to build on.
- **What the new tests cost, and what they found.** 17 tests in four new classes plus five cases in
  `SettlementCommandTest`: opening leaves no run, opening twice is one cycle, a cycle opened now is the cycle computed
  later - the composition claim, which is the whole design in one assertion - a pass computes what was opened, does not
  touch what already has a plan, refuses and reports a group with nothing to settle, keeps going after a refusal, keeps
  going after a fault, serves the oldest request first, and stays inside its batch. `ScheduledRunComputerTest` covers
  the cadence's seam without a database, as `ScheduledRunDriverTest` does for the driver. **The tests found one real
  defect and one wrong test.** The defect was `describe`'s throw, which the fixture-dependent tests hit immediately: an
  invariant that had been true only as long as two acts were one act. The wrong test was mine - I asserted that a batch
  of one drains the queue to empty, which cannot hold while a refused cycle stays in it; the *code* was right and the
  assertion came from the design's intent rather than from its behaviour, which is the failure mode the plan already
  names for assertions about "nothing moved".
- **A cycle with no run is a state the API reads nothing of, which is its own finding.** `apps/api` reads
  `settlement_runs` (the client's runs list, the preview's exclusion of settled commitments) and never
  `settlement_cycles`, so a cycle that exists and has no plan is invisible to every existing reader. That is correct
  today - there is nothing to show - and it is the thing the API slice has to change: a member who has asked for a
  settlement has to be able to see that they asked.
- **The cost of the missing attempt marker is bounded, and it is stated where it is paid.** With N cycles that cannot
  be computed, one pass does N refusals; the batch bounds N, the log names them, and `compute-opened` prints them. A
  column recording an attempt would remove the cost and *lose* the backdated-obligation case unless it also expired,
  which is why it is a migration with its own decision rather than a column added here.
- **Three contract gaps are recorded rather than latent**, because this is the slice that noticed them. The contract's
  `POST /groups/{groupId}/cycles` takes `{action: open|close, periodStart, periodEnd}`: `open` needs the *instant* that
  keys a cycle and has only dates, and `close` has nothing to write to, because `settlement_cycles` has no status
  column at all. `POST /cycles/{cycleId}/runs` is now redundant - the cadence is the trigger, and a client that knows a
  cycle's id has nothing left to ask for - and a client cannot learn a cycle's id anyway, because
  `POST /groups/{groupId}/cycles` answers `201` with no body. The API slice has to amend the contract, and this ADR is
  the record that it must rather than a licence to implement the node as written.
- **The API slice has one decision left to make, and this ADR does not make it.** Either the API records a request in
  its own table (`(group_id, as_of, requested_at)`, unique on the pair) which the worker's pass consumes by calling
  `OpenCycle` - no logic of this module is copied, and the request becomes a first-class thing a schedule could also
  write - or the API writes the cycle row itself, which is ADR 0020's crossing pattern and would duplicate an
  *identity derivation* rather than a column list. The second is cheaper and touches a key scheme, which is the
  difference between copying a shape and copying a rule; the first is what `V501`'s comment hinted at and is the shape
  this ADR would choose.
- **Nothing about identity, membership or the settlement policy changed.** Cycles are still opened for whoever the
  caller names, quorum is still over a run's own participants (ADR 0016), and when a cycle comes due is still a group's
  decision that no build here can express.


## Alternatives considered - and why rejected

**A cadence that computes whatever is open, on an interval.** The obvious way to "close the gap", and a policy invented
in the place least able to defend it: "settle whatever is open every time the job runs" would compute a plan over a
period nobody chose, and it would make the instant a function of when a cron entry fired. The spec's sentence that the
policy "belongs with the group" is why this is refused, and it is why `open` is the one command with no cadence.

**Extending `DriveRuns` so one tick computed and drove.** Fewer moving parts, and two bounds welded together: driving
is rate-limited by rail calls and computing by the planner, so a deployment that wanted to slow one down would slow the
other. Two classes with two property sets is the smaller coupling.

**Making the API write the cycle row now.** Rejected for this slice, and named above as the next slice's decision
rather than a settled one. The cost that decided it: `cycleIdOf` is a derivation inside this module, and a second
implementation of it fails silently - a cycle nothing can compute - rather than loudly.

**An `attempted_at` column on `settlement_cycles`, added here.** It would remove the every-pass retry, and it would
need an expiry to keep the backdated case: a commitment recorded today can belong to a cycle from two months ago, so
"attempted and refused" is not a permanent answer. A migration whose reason is mostly a cost that is currently bounded
is a change to make when the cost bites, and the plan records it as the fix.

**Ordering by `created_at` descending (newest first).** Considered and rejected in decision 6: it optimizes for the
person who just asked at the expense of the person who has been waiting longest. What makes it tempting is that a stale
request is the cheapest thing to serve late; what makes it wrong is that a stale request is also the one nobody has
answered.

