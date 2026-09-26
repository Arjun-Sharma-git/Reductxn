# ADR 0024 - A silence is not evidence, so escalation is a report

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`V508`, `Escalation`, `ReadEscalations`, `SilentTransfer`, `Escalations`,
  `JooqRuns.findSilentTransfers`, `ScheduledRunDriver`, `SettlementCommand escalations`), and the terminal-run guard
  in `ExecuteRunService`
- **Related:** ADR 0018, 0019, 0022; RULE S2, S3, B17, B21; `docs/specs/settlement.md` sections 4.2, 4.4
- **Amended by:** ADR 0026 - the report this ADR introduced now carries a second reason (a rail that has the
  instruction and will not confirm it), so its row type is `StuckTransfer` rather than `SilentTransfer`, and the two
  policies that draw its lines are `Escalation` and `Reconciliation`

## Context

Section 4.4 ends with "or escalate", and it was the last part of that section without an implementation. The
machinery in front of it is complete and deliberate: a rail that does not answer is retried with an exponential,
capped backoff (`Backoff`, `V506`), an answer of any kind resets the counter, a circuit breaker stops calling a rail
that is down, and the driver polls a transfer nobody has decided *for ever*. What nothing did was decide that "for
ever" had stopped being patience - the spec said so in as many words ("nothing decides when patience has run out,
and nothing tells a human"), and added that the policy was deliberately not invented there because inventing it is a
decision about a deployment rather than about settlement.

This ADR is that decision, and it turned out to have a second half. The first half is when the worker should stop
treating a silence as business as usual. The second is what it is then *allowed to say*, and the answer is less than
it looks: nothing.

## Decision

**1. Patience is a duration, and the duration has to be stored.** `Escalation` is one number - how long a rail may go
unanswered before somebody has to look - configured as `reductxn.settlement.escalation.after` and defaulting to two
hours. `V508` adds `settlement_transfers.unanswered_since`: the first unanswered call of the current spell, kept
across the attempts that follow it and cleared by any answer.

The cheaper policy is a count of tries ("escalate after eight unanswered calls"), which needs no new column, and it
is wrong for a reason worth writing down: with an exponential backoff the same count means a *different amount of
time* the moment `retry.base` or `retry.cap` is retuned, so a deployment change would silently move the escalation
line by hours. Patience is a statement about time, so it is measured from a time.

**2. The policy computes a cut-off, and a query compares against it.** `Escalation.cutOff(now)` returns the instant a
silence must have begun at or before to be past patience, and `Runs.findSilentTransfers(cutOff, limit)` finds the
rows. This is the driver's window's arrangement, deliberately: how stale an attempt must be (or how long a silence
may be) is configuration, and the comparison happens where the rows are. It is also what makes a two-hour rule
assertable in a test that never waits.

`Escalation` lives in `port.in`, beside `Quorum` and `Funding`. That is not where a "policy" would naturally be put -
`Backoff` is in `application` - and it is where it has to be: the two callers that need it are the cadence and the
command line, both adapters, and RULE B14 keeps adapters off `application` types. The build said so before this
paragraph did, which is the rule working. The placement is also right on its own terms: this is a rule about
settlement that a caller may hold and a test can construct, exactly as `Quorum` is.

**3. Escalation tells; it does not decide.** The worker cannot resolve a silent transfer, and the reason is not
caution but evidence: the absence of an answer is not evidence of anything except silence. Marking the transfer
`failed` would assert that the money did not move, and marking it `confirmed` would assert that it did - and in the
case that matters most (`submitted`: the rail accepted the instruction and then went quiet) nobody knows which. So
the only write escalation makes is the record of the silence itself, and the telling happens in two places a human
already looks: a warning line on each cadence pass, and `--reductxn.command=escalations` on demand.

**4. The line repeats every pass, and nothing records that it was said.** The tempting shape is an `escalated_at`
column and a "we have told them" rule. It would be a memory of a log line: a silence that has not ended is still
happening, and a worker that announced it once would let the second hour, and the twentieth, pass unnoticed. The
evidence is the rows; the repetition is the point; and a deployment that wants "tell me once" has a log aggregator
that can do that and a policy number it can change.

**5. The report answers "how bad" and "which ones".** `Escalations` carries the cut-off it was drawn at, the total
count, and the oldest page of transfers (bounded by a limit, because a rail down for a month must not turn a log
line into a novel). A truncated list says so rather than implying it is the whole problem - the count is the number
an operator decides with.

**6. `status` is part of the report, because it changes what a person does.** `submitted` means the rail has the
instruction and may have moved the money, so the question to chase is whether it did; `authorized` means the rail
never took it, so nothing has happened yet. A report that said only "unanswered" would hide the first case behind
the second.

**7. A run that has failed is not resumed.** Found while writing the tests for this: the guard at the top of
`ExecuteRunService` covered `done` and not `failed`, so a manual `drive --run=<a failed run>` offered that run's
remaining `authorized` transfers to the rail again - money moving for a cycle that had already been reported as not
settling. `failed` is now answered with itself, like `done`. A `rejected` run deliberately keeps the gates' refusal
instead, because that refusal names the member whose answer ended the run, which is more use to a caller than "this
run is over".

## Consequences

- **An operator can find out.** What no rail will decide is now a thing the deployment says out loud, with the rail's
  own last words and how long it has been quiet, rather than something a person reconstructs from `rail_detail` and a
  queue nobody looks at.
- **Nothing can be resolved, and that is a named gap rather than an oversight.** The next step is an operator who has
  *learned* from the rail that the money moved or did not, and a command that records it. That needs the same booking
  path a confirmation uses (one entry, derived from the transfer, in the same transaction) and an answer to a
  question this ADR does not settle: who may assert that money moved. The report is what makes that step possible at
  all, because it is what tells the operator *which* movement to ask the rail about.
- **A silence now has a start in the rows, which fixes an ambiguity nobody had hit yet.** "Eight unanswered calls"
  was readable as a duration by whoever knew the current backoff settings; `unanswered_since` makes the duration a
  fact.
- **The escalation report reads transfers rather than runs, and that is why it still works when a run is over.** A
  transfer left in `submitted` inside a `failed` run is the worst case for a person to have to guess about - the
  run says the settlement did not happen, and the rail may have moved the money anyway - and it is exactly the case
  a run-shaped report would have dropped.
- **Amended since: the report grew a second reason, and the person it told can now answer it.** ADR 0025 gave the
  operator a command for what the report tells them to ask about. ADR 0026 then found that the report could not see
  a transfer a rail had *accepted* and would not confirm - such a rail is answering, so no silence ever begins - and
  closed that gap by storing the acceptance instants and measuring a second policy against them. The decision here
  is unchanged: the report tells, and what it tells about is now everything no rail has decided.

## Alternatives considered - and why rejected

**Escalate after a number of tries.** Rejected in point 1: the number means a different amount of time the moment the
backoff is changed, which makes the policy a function of a different policy.

**An `escalated_at` column, and tell them once.** Rejected in point 4: the state would exist only to suppress
repetition, and repetition is what a monitoring line is for.

**Mark the transfer `failed` when patience runs out.** Tempting, because it takes the problem out of the worker's
hands - and it asserts something nobody knows: that the money did not move. A `submitted` transfer that has gone
quiet for two hours may well have settled, and the ledger would then be missing a movement that happened. The
spec's own RULE S3 says a partial failure books *what moved* and nothing else; this would book the opposite.

**A new transfer state - `stalled`, or `escalated`.** A state in this lifecycle carries rules with it: what may
follow it, whether it books (ADR 0017), whether the run is settled or not. "Somebody is looking at this" is not a
state of the money, and inventing one would put a monitoring fact into the vocabulary the ledger and the run's
outcome are computed from.

**Put the report behind an API endpoint.** The worker has no HTTP surface and must not grow one (the command line
exists for exactly this), and escalation is not a member's business: a member must not be told that the rail is
having a bad day with somebody else's money. What a member is owed is the state of their own run, which the run's
status already carries.

**Wire it to a pager or an email.** No such infrastructure exists in this build, and inventing an outbound channel
to carry a report would be a larger decision than the report. The log is the deployment's channel: it goes wherever
that deployment already sends logs, which is where its alerts are configured.

**Escalate in a use case rather than in the cadence's read.** The policy does not act on a run, and the two things
that need the report - a cadence with a log and an operator with a shell - are both adapters. A use case would have
had to decide which of them it was for.

**Ignore the failed-run hole for now.** Rejected because the test for this slice failed for two different reasons at
once, and the second reason was a live hole: the guard against resuming a finished run covered `done` only. A slice
about what happens when nothing can decide a transfer is exactly the place to notice that a run which has been
declared over could still be driven into moving money.
