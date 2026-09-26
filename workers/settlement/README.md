# workers/settlement

**Purpose:** owns the settlement run: lifecycle, quorum, idempotency, rail submission, funding state. Runs the
netting solver via `libs/netting`, the per-currency plan via `libs/planning`, and books what its rail confirmed
via `libs/booking`.

**Public surface:** none - it publishes events and writes its own tables. It has no HTTP surface at all, and
`spring.main.web-application-type=none` is set so that stays true: an endpoint here would be a second write path
to the same rows with none of the API's validation or error vocabulary.

**Owns tables** (RULE B17), versions `5xx`:
`settlement_cycles`, `settlement_runs`, `settlement_partitions`, `net_positions`, `settlement_transfers`,
`settlement_run_obligations`, `settlement_decisions`, `settlement_fx_fundings`.

**May depend on:** `libs/kernel`, `libs/ledger`, `libs/netting`, `libs/planning`, `libs/fx`, `libs/booking`,
`libs/rails`, `platform/*`.

**Also writes, and does not own:** `journal_entries` and `postings` are `libs/ledger`'s schema, and this module
appends settlement entries to them - the second writer of that ledger, because it is the only process that knows
a movement happened (ADR 0017). The booking *shape* comes from `libs/booking` (RULE B22), so two writers cannot
disagree about what an entry is.

## Running it

**As a daemon** (the default): `java -jar workers-settlement.jar`. Two cadences run: one drives every run whose
progress is possible, and one computes the cycles somebody has opened and nothing has computed (ADR 0032) -
`reductxn.settlement.drive.interval` / `.window` / `.batch-size` and `reductxn.settlement.compute.interval` /
`.batch-size`, or the `REDUCTXN_DRIVE_*` and `REDUCTXN_COMPUTE_*` environment variables. This is also what keeps
the process alive: it is a non-web application, so without a non-daemon thread the JVM exits after startup.

**As a scheduled job**: set `reductxn.settlement.drive.enabled=false` and `reductxn.settlement.compute.enabled=false`,
and let cron do it -

```
java -jar workers-settlement.jar --reductxn.command=compute-opened
java -jar workers-settlement.jar --reductxn.command=drive
```

**By hand**, which is also how an operator opens a cycle (when a cycle is due is a group's schedule, and groups do
not exist):

```
# open a cycle, and compute nothing: a settlement policy decides the instant, and the plan follows from it
java -jar workers-settlement.jar --reductxn.command=open \
     --reductxn.group=<uuid> [--reductxn.as-of=<instant>]

# open a cycle and print the plan, in one act
java -jar workers-settlement.jar --reductxn.command=compute \
     --reductxn.group=<uuid> [--reductxn.as-of=<instant>]

# compute every cycle that was opened and has no run - the cadence's pass, from a shell
java -jar workers-settlement.jar --reductxn.command=compute-opened [--reductxn.limit=50]

# one pass over what is due, or one specific run
java -jar workers-settlement.jar --reductxn.command=drive [--reductxn.run=<uuid>]

# record currency arriving, under the reference it is known by outside reductxn
java -jar workers-settlement.jar --reductxn.command=fund --reductxn.run=<uuid> \
     --reductxn.currency=JPY --reductxn.amount-minor=224 --reductxn.exponent=0 \
     --reductxn.funding-ref=<reference>

# what no rail has decided: silent too long, or at the rail unconfirmed too long
java -jar workers-settlement.jar --reductxn.command=escalations [--reductxn.limit=50]

# what a person found out about one of them (RULE S6): the way back from the report above
java -jar workers-settlement.jar --reductxn.command=resolve --reductxn.run=<uuid> \
     --reductxn.transfer=<uuid> --reductxn.outcome=confirmed|failed \
     --reductxn.by=<who> --reductxn.basis=<what they looked at>
```

Exit codes: `0` done, `1` refused by the domain, `2` the invocation was wrong. **No command approves or rejects a
run** - answering is a member's act, and the member's surface is `apps/api` rather than an operator's command
(ADR 0018, 0020).

**Opening a cycle and computing it are two commands, and the difference is a policy (ADR 0032).** `open` writes the
cycle and nothing else: a cycle says *which commitments are being settled* - everything recorded at or before the
instant - and that is a decision a group makes before any plan exists, so a period can be closed today and settled
tomorrow. `compute-opened` is the reply: it computes every cycle that has no run, and it is what the cadence runs, so
a cycle opened by a group's schedule (or, next, by a member through `apps/api` since ADR 0033) is computed without a person. What
neither command does is decide that a cycle has come due - that is the group's, and it is why `open` is the one
command with no cadence.

## Who writes a run's decisions

Another deployable does. `apps/api` records a member's answer into `settlement_decisions` through
`POST /v1/runs/{runId}/decisions`, because the member's surface is there and the run is here, and the two modules may
not depend on each other (RULE B6). It is safe for a reason rather than by trust: quorum is a pure function over
the run's own rows (ADR 0016), so the answers are intersected with the run's participants and an answer from somebody
the run does not concern counts for nothing, whoever wrote it. The worst a mistaken write can do is record one
member's approval.

**Both answers cross that boundary, and they do not mean the same thing.** An approval is counted; a refusal ends the
run. So `ExecuteRunService`'s gate refuses any run whose stored decisions include a refusal *and writes the run's
fate* - `rejected` - when the stored status has not caught up. That is the piece a status written from outside cannot
provide: the API records rows and not states (ADR 0020), so without it a refusal would leave a run reading
`awaiting_approval` for ever while its own rows said the group said no. The write lives beside the gate rather than in
the driver, because the driver's design is that it decides nothing and the gate already reads the decisions
(ADR 0022).

The crossing is pinned from this side by `RecordedDecisionsTest`, which writes the same rows with the same SQL as
`apps/api`'s adapter and asserts that the run's arithmetic judges them: two approvals recorded from the API settle a
cycle with nothing else touching the worker, one is not a quorum, a stranger's answer - either word - counts for
nothing, and a refusal written from outside leaves the run waiting until this worker's next look ends it. The
vocabulary is fixed by `V502`'s check constraint, so the other deployable writes words it does not own, and a word
the run does not know is refused by the row rather than by a list either module keeps.

## What the worker writes down for readers

Everything in this worker is derived from a run's rows, and the worker derives it on every read. A reader in
`apps/api` cannot: whether a run has quorum needs its participants and who owes what, and the funding requirement is
the plan's arithmetic - so a reader that answered "why is this waiting?" would be implementing the settlement rules a
second time (ADR 0021). `settlement_run_gates` is the answer (ADR 0023): `ExecuteRunService` writes the verdict it
just read - quorum met with the approvals counted and required and how many debtors still have to confirm, the
funding gaps per currency, the status the verdict was reached for, and when it looked - and `GET /runs/{runId}`
projects it.

The verdict is `GateConclusion.of(run, instant)`, which *reads* `Quorum` and `Funding` rather than deciding
anything: one implementation of each rule, and the stored row is a copy of its answer. It is written on every look
(unconditionally, so `concluded_at` means "when the worker last read these gates"), it is replaced in place rather
than accumulated - a conclusion is not a history - and its own `CHECK`s refuse a verdict that contradicts itself,
such as "quorum met" with a debtor still to confirm.

`apps/api` writes these rows in its own test fixture by SQL, the way it writes decisions, because it cannot conclude
anything; this module's `GateConclusionTest` asserts that its writer produces exactly those columns and that they
agree with the verdicts it derives. The two tests pin one row from both ends.

## What no rail has decided

Two things stop the worker being able to settle a transfer, and neither of them is a failure it can fix. A rail that
never answers leaves a *silence*: the transfer is retried with an exponential, capped backoff and then polled for
ever, and the escalation policy (`reductxn.settlement.escalation.after`, two hours by default) says when "for ever"
has stopped being patience. `V508` stores when the current spell of silence began, so that duration is measured from
a time rather than from a count of tries - eight tries means a different amount of time the moment `retry.base` or
`retry.cap` is retuned.

**A rail that answers and will not confirm is the other one (ADR 0026).** It takes the instruction, so the transfer
is `submitted`, and then it keeps saying "still pending": nothing goes unanswered, the retry counter resets on every
poll, and the movement can sit at the rail for a week while every measure the worker had said the transfer was
healthy. `V510` stores when the rail accepted it - the first acceptance, kept across the polls that follow, so the
wait cannot restart whenever the rail speaks - and `reductxn.settlement.reconcile.after` (one hour by default) is the
threshold for that. It is a separate number because it is a separate question: a rail holding the group's money is a
different thing from a rail that has stopped picking up the phone.

**What the worker does with both is say so, and nothing else (ADR 0024).** Each pass logs a warning naming every
transfer past either line, the reason (`no_answer` or `no_confirmation`), how long it has been true, the state the
rail left it in and the rail's own last words; `--reductxn.command=escalations` prints the same listing on demand.
The line repeats every pass, because trouble that has not ended is still happening, and the listing is one report
rather than two because the person on call has one question.

**It does not resolve them, and that is the decision rather than an omission.** A silence is not evidence of anything
except silence, and an acceptance is evidence only that the rail has the instruction: marking such a transfer
`failed` would assert the money did not move, and marking it `confirmed` would assert that it did. So the report is
what tells a person *which movement to ask the rail about*, and the resolution below is how they answer it.

## When a person knows the answer

`--reductxn.command=resolve` is the way back from the report (ADR 0025, RULE S6). It takes the run, the transfer,
the outcome (`confirmed` or `failed`), `--reductxn.by` and `--reductxn.basis` - all five required - and writes the
transfer's state with `V509`'s `resolved_by`, `resolved_basis` and `resolved_at` beside it. An unpriced claim about
money cannot be built: `TransferResolution` refuses an attribution in pieces, so there is no way to record that money
moved without saying who says so and what they looked at.

**Nothing verifies the name.** There are no identities in this build - membership, sessions and roles are
`apps/api`'s and do not exist yet - so this is an *attribution*, not an authentication, and the ADR says so rather
than implying otherwise. When identities exist, the column becomes a foreign key and the command becomes an act
somebody was authorised to take.

**The write is deliberately small.** It calls no rail (the operator already has the answer, and asking again would
make the command depend on the very silence it exists for), touches no other transfer (an operator resolving one
movement has not asked for the run to be attempted), and books exactly what a confirmation books - the same
`Bookings.settlement` entry derived from the transfer, in the same transaction as the state write - or nothing at
all when the answer is that the money did not move. It clears the silence, so the report stops naming the transfer
it came from, and it lets `RunOutcome` decide the run's fate: the rule `ExecuteRunService` used, extracted so that
two writers of one fact cannot disagree.

Three things are refused, each because the assertion cannot be true of the row: a transfer that already has an
answer (and the refusal names who decided, when and on what basis, which is what an operator re-running after a slow
response needs to see), a transfer no rail was ever asked about (`pending` - there is no answer to have found out),
and a transfer that is not in the run named. What is *not* required is that the transfer be past
`escalation.after`: patience is the worker's prompt to a human, not a permission a human needs.

One thing this slice found and fixed: a run that has `failed` was not covered by the guard that stops a finished run
being driven again, so a manual `drive --run=<a failed run>` offered that run's remaining `authorized` transfers to
the rail. A run that is over is answered with itself now, like one that is `done`.

## When a rail does not answer

A refusal is classified by the port itself, and the worker does different things with the two kinds (ADR 0019):

- **`rails.rejected`** - the rail answered that it will not take the instruction. The transfer is `failed`, the
  run is `failed (partial)` (RULE S3), and no later pass offers it again.
- **`rails.unreachable`** - the rail did not answer, or the breaker is leaving it alone. The transfer stays where
  it was and waits; nothing is lost and nothing is marked failed that was never refused.

The wait doubles from `reductxn.settlement.retry.base` (30s) to `reductxn.settlement.retry.cap` (15m), and the
count it doubles from is *consecutive* unanswered calls: any answer resets it, so a rail that is working is never
made to wait for failures it has recovered from. After
`reductxn.rail.breaker.failures-before-opening` (5) unanswered calls the breaker stops calling the rail for
`reductxn.rail.breaker.cooldown` (30s), then allows one call through to test it. The breaker's state is in
memory, so restarting the worker clears it.

**When a run looks stuck, the three columns to read are on `settlement_transfers`:** `status` (where it is),
`rail_detail` (what the rail or the breaker said, in their own words), `unanswered_attempts` and `next_attempt_at`
(how many times in a row it went unanswered, and when it will be tried again). A run whose transfers are all
waiting is not driven at all, so `settlement_runs.driven_at` standing still is the ordinary sign of a rail that is
being left alone rather than of a worker that has stopped.

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
  ones left confirmed (RULE S3), and `executing` while the rail has not answered. **Each confirmation is booked**:
  one `settlement` entry per movement, committed in the same transaction as the transfer's state, so the ledger
  says what the run did rather than what it intended (ADR 0017).

All eight lifecycle states are therefore reachable.

**Settling is terminal.** A run records the commitments it nets when it is computed
(`settlement_run_obligations`), and an obligation a `done` run lists is not open again - in this module's own
compute and in `apps/api`'s preview, both of which apply the same rule. Without it a later cycle would net the
same debts and move the group's money twice for one promise. A `failed` run settles nothing, so its commitments
stay open (RULE S3).

**Two adapters trigger it, because different things have to be triggered by different actors** (ADR 0018).
`ScheduledRunDriver` is the cadence: on a timer it offers every run whose progress is possible to `ExecuteRun`,
least recently attempted first and bounded by a window and a batch size (ADR 0019's classification is what it does
with the two kinds of refusal). `SettlementCommand` is the operator's shell for what only a person can decide -
opening a cycle (when a cycle is due is a group's schedule, and group schedules do not exist), recording that
currency arrived, reporting what no rail will decide, and recording what one turned out to have done.
`docs/specs/settlement.md` section 8 has the detail, with a reason per remaining gap: a *cycle* still has no
trigger, a member's approval reaches the worker only through `apps/api` (ADR 0020), nothing posts the currency a
group brings in from outside (the group account is the ledger's open question), and the reconciliation sweep's
detection exists while its job and threshold do not.

**The rail is a deployment choice, named rather than assumed.** `reductxn.rail.name` selects it (default `mock`,
reached through `libs/rails`'s factory because its implementations are internal - RULE B10), and an unknown name
fails at startup rather than falling back: a worker that moved money through a rail nobody configured is the
failure that line exists to prevent.

**Owned-table note:** the fourth and fifth tables arrived with the first run, and two more with quorum and
funding. `settlement_run_gates` and its shortfall rows arrived with ADR 0023, and `V503`-`V510` have added columns
rather than tables: what the rail said about a transfer, the retry wait, the spell of silence an escalation policy
measures, the verdict's fields, when a rail accepted a transfer, and - with ADR 0025 - the three that record who resolved one, on what basis
and when. `settlement_partitions` exists because the plan's per-currency figures - gross, net, tax, tax saved -
cannot be re-derived from the transfers: they are the netting engine's answers about the partition, and
re-deriving them would mean running the engine again to read a run. `settlement_decisions` is keyed by
`(run_id, member_id)`, which is what makes a decision final at the database and not only in the guard that checked
first. `settlement_fx_fundings` is keyed by the caller's `funding_ref`, which is the same idempotency rule (S2)
applied to the other thing in this module that can half-happen.

**Tests:** `./gradlew :workers:settlement:test` (Testcontainers, real Postgres; 131 tests across seventeen classes).
The tests drive the inbound ports rather than the service classes, and they verify *stored* state: per-currency
zero-sum read back from the rows (N1), every participant zeroed by applying the plan's own transfers (N2), the
clearing account's residual, that the rail was asked exactly once per transfer and not at all when a gate
refused, and - for the ledger side - that the entries rebuilt from their own rows verify as a chain
(`EntryChain`) and leave every position flat (`Balances`, L8).

Several of those classes are about the *trigger* rather than the rules. `DriveRunsTest` executes runs through the
driver alone - no test touches `ExecuteRun` - and asserts that a run which cannot progress is left where it was
with the gate's reason, that a transfer a rail never confirmed is resolved by a later pass, and that a pass is
bounded and takes the least recently attempted first. `SettlementCommandTest` walks the operator's command line
against the real bean and the real database, including an approved, funded run settled to `done` from a shell and a
resolution recorded from one. `StuckTransfersTest` is about the two policies - it moves the clock hours forward to assert what patience and a
stale submission mean - and `ResolveTransferTest` is about what a person's answer may and may not do, including the cases
the worker cannot decide for itself. `RailRetryTest` and `BrokenRailTest` cover a rail that does not answer, and they
move time rather than sleeping: `MovingClockConfiguration` replaces the clock bean, which is possible because nothing
below `application` reads a clock of its own.

**Owner:** backend reviewer.

