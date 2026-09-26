# ADR 0023 - The worker writes its verdicts down for readers that cannot compute them

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`GateConclusion`, `Runs.recordGates`, `V507`, `ExecuteRunService`),
  `apps/api` (`RunReport.Gates`, `GET /v1/runs/{runId}`), the OpenAPI contract's `RunGates` schema
- **Related:** ADR 0012, 0016, 0018, 0020, 0021, 0022; RULE S1, S4, B6, B17, B21

## Context

The read endpoint made a run visible and refused to judge it: it projects rows - the plan's figures, the movements,
each answer - and says nothing about whether the run *may* go (ADR 0021). That refusal is right, and it left the
sentence a member most needs unsaid. "Why is this waiting?" is quorum's arithmetic (a majority of the run's members,
and every debtor personally) and the funding requirement (which currencies the plan cannot settle internally, net of
what has arrived). Neither is a row. A member could read the answers and the status and combine them, which is
asking a member to implement the settlement rules in their head.

ADR 0021 named the eventual fix and ADR 0022 repeated it: the worker should record what its gates concluded. This
ADR is that decision, and the choice inside it is older and wider than the field it adds - **a rule can cross a
module boundary as a stored verdict or as shared code, and those are not the same thing.**

The module that owns both rules is `workers/settlement`: `Quorum` holds the majority-and-debtors arithmetic over the
run's participants, `Funding` holds the requirement and its coverage, and `ExecuteRunService.gates` is the only place
either is read. The module that has the member's surface is `apps/api`, which may not call the worker (RULE B6) and
does not read the run's net positions at all.

## Decision

**1. The verdict is stored, one row per run, and the reader projects it.** `settlement_run_gates` holds
`quorum_met`, `approvals_counted`, `approvals_required`, `debtors_still_to_confirm`, `funding_satisfied`,
`concluded_at` and the status the verdict was reached for. `GET /v1/runs/{runId}` reports them as they stand.

**2. The verdict is written by the only code that reads the gates, and derived once.** `ExecuteRunService` writes it
immediately after the gates are read, on every look, before it branches on any refusal - so a run that is about to
execute also says what the gates said on the way in. The values come from `GateConclusion.of(ComputedRun, instant)`,
which *reads* `Quorum.met()`, `countedApprovals()`, `required()`, `outstandingDebtors()` and `Funding.satisfied()`,
`outstanding()` and decides nothing. There is one implementation of each rule in the process, and the stored row is a
copy of its answer rather than a second opinion.

**3. `concluded_at` means "when the worker last read these gates", and the write is therefore unconditional.** The
tempting optimisation is an upsert that skips the write when nothing changed - fewer locks, fewer WAL records. It
would quietly change the field's meaning to "when this verdict was first reached", which is a different and less
useful fact: a reader wants to know how current the answer is, not how long ago the situation started. The write is
bounded instead by the driver's due query: only a run something can still happen to is looked at.

**4. The verdict carries the status it was reached for.** A projection outlives its subject. A run that was
`awaiting_approval` when the gates were read and is `done` now keeps the verdict it had - correctly, because that is
what the gates said - and without the version in the row a member could not tell "one approval short" from "one
approval short last Tuesday". A client compares `gates.status` with the run's own `status`.

**5. `debtors_still_to_confirm` is a count, not a list.** The rule's explanation needs the count: "a majority has
approved and a debtor has not confirmed" is `met = false` with `debtors_still_to_confirm > 0`, which is the
combination a member finds hardest to understand. *Which* member is a member-level fact, and the run's members are
`net_positions`' - writing the list here would be the second place the member set is stored, refreshed on every look.
Publishing the run's participants is a different gap, and a better fix for "who".

**6. The funding gaps are rows, and only the gaps.** One row per currency, with the amount's exponent travelling
beside it, because an amount carries a currency and an exponent and nothing here adds rupees to yen (ADR 0012 point
4). A currency that is covered has no row - the same choice `Funding.outstanding()` already makes - so the list *is*
what the group still has to do, and no reader has to filter zeros out of it. The row's own `CHECK` refuses a
non-positive gap, which is that decision at the database.

**7. The invariants a verdict cannot contradict are constraints.** `NOT quorum_met OR approvals_counted >=
approvals_required` and `NOT quorum_met OR debtors_still_to_confirm = 0`: both follow from `Quorum.met()`, and a
writer that got either wrong would be storing a conclusion nobody could act on. `V502` already puts a rule in the
schema; this is the same habit applied to a verdict.

**8. Only the two gates that a group can act on.** `ExecuteRunService` also refuses a run whose currencies the
configured rail cannot settle. That is not in the verdict, and the reason is what kind of fact it is: it is the
rail's declaration rather than anything about the run, and no member's action clears it - the operator who wired the
rail does. A verdict is what a *group* is waiting for; a deployment mistake is reported to the operator who made it.

## Consequences

- **A member can now be told how far off they are.** `counted` against `required`, and `debtorsStillToConfirm`, from
  the worker's own arithmetic - with the caveat that the *identity* of a missing debtor is not published (named in
  point 5, and the honest fix is publishing the run's participants).
- **The `fxRequirements` gap narrows rather than closes.** The contract's field is the plan's total requirement;
  `gates.funding.outstanding` is what is still missing, which is the actionable half and a different fact. The total
  remains unpopulated (ADR 0021 still applies to it), and now the difference is visible to a reader rather than being
  a single unexplained absence.
- **A freshly computed run has no verdict until its first drive.** The window is one cadence, and the read model
  reports it as an absence rather than as an invented "not met". The alternative - concluding at compute time too -
  is one of the alternatives below.
- **Terminal runs keep their last verdict, and it says which run it was about** (point 4). A client reading `gates`
  for a `done` run learns what the gates said before it executed, which is a true and useful fact rather than a
  contradiction.
- **The conclusion is not a history.** One row per run, replaced in place: a reader with two conclusions would have
  to answer which one is current, and a table with a version column nobody reads is a table that grows for nothing.
- **One more write per look at a waiting run.** Not per pass, and not for runs in a terminal state: the driver's due
  query excludes those, so the cost is bounded by the number of runs something can still happen to.

## Alternatives considered - and why rejected

**Put the rules in a shared library and let the API compute them.** The structurally tempting option: `Quorum` and
`Funding` are arithmetic over rows, `libs/*` is where shared code lives, and `apps/api` could call them and answer
"why is this waiting" itself with no new table. Rejected for three reasons that are really one. It would make the
reader a judge: two deployables able to decide whether a group may settle, and no way for a member to tell which one
answered. It would need facts the reader does not have - the run's participants are `net_positions`, which this read
model does not publish, so the API would have to start reading the run's membership to answer a question about a
*status*. And it would put a rule about money behind a query handler, where a read can become a decision one caller at
a time. A verdict is written by the code that acts on it, at the moment it acts; that is what makes the reader's
answer the same answer the worker's gate uses.

**Have the API compute the verdict into a view over the worker's tables.** Same objection, once removed: the
arithmetic would live in SQL in the API's migration range, which is worse - the rule would be defined in a language
nobody writes tests in, in a module that owns neither the tables nor the rule.

**Conclude at compute time as well, so a fresh run has a verdict immediately.** One cadence of absence is a real
cost for a member who has just computed a run and is looking at it. Rejected because it would put the same projection
in two use cases: the conclusion would be written by the code that opens a run and by the code that reads its gates,
and the two would eventually disagree about a run nobody had driven - the disagreement being a member reading a
verdict that no gate produced. The absence is honest and short; a second writer would be permanent.

**Store the verdict only when it changes.** Rejected in point 3: it changes what `concluded_at` means.

**Store the verdict as a JSON document.** One column, no schema churn, and every reader parses it. Rejected because
it is opaque at exactly the places this schema is explicit: no `CHECK` can refuse a verdict that contradicts itself,
no foreign key ties a funding gap to its run, and a reader would be written against a document's shape rather than
against columns the migrations describe.

**Store the outstanding debtors as a list.** Rejected in point 5 - the member set belongs to `net_positions`, and a
list refreshed on every look is how a second copy of it starts.

**Store the rail-support gate too.** Rejected in point 8: it is not a fact about the run and no member can clear it.

**Have the driver write the verdict.** Rejected on ADR 0018's terms, and on ADR 0022's for the same shape of problem:
the driver decides nothing, and the gates are read in `ExecuteRunService`. A driver that wrote verdicts would be a
second place where "what is this run waiting for" is answered.
