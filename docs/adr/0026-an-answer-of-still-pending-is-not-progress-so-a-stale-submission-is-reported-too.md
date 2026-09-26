# ADR 0026 - An answer of "still pending" is not progress, so a stale submission is reported too

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`V510`, `Reconciliation`, `StuckTransfer`, `StuckTransfers`,
  `ReadStuckTransfers`, `ReadStuckTransfersService`, `Runs.findStuckTransfers`/`countStuckTransfers`,
  `JooqRuns.stuck`, `ScheduledRunDriver`, `SettlementCommand escalations`)
- **Amends:** ADR 0024 - the report it introduced now carries two reasons, and its row type has a name that says so
- **Related:** ADR 0018, 0019, 0024, 0025; RULE S2, S3, B15, B17; `docs/specs/settlement.md` section 4.4
- **Closes:** section 4.4's fourth requirement ("a reconciliation sweep polls for transfers stuck in `submitted`
  beyond a threshold") and the column `V503` promised for it

## Context

Section 4.4 asks four things of a rail adapter, and the fourth is a sweep: "a reconciliation sweep polls for
transfers stuck in `submitted` beyond a threshold". Everything around it was built and this was the part left
partly done, with the gap written down twice. `V503` promised the column - "the reconciliation sweep will measure a
stuck transfer against the moment it was submitted, and that means a timestamp and a partial index on
`status = 'submitted'` - but nothing reads either today, and a column nothing reads is a claim rather than a fact.
It arrives with the sweep, in one `ALTER TABLE`, which is what the version range is for." And the spec's
implementation status recorded the difference it had made: the sweep's threshold had moved up a level, to the run
(`settlement_runs.driven_at`), because a run is the unit of work - so the polling existed and the per-transfer
threshold did not.

ADR 0025 then found the consequence while writing a test. A transfer whose rail accepts the instruction and never
confirms it is `submitted`; if the rail keeps *answering* - "still pending" - then no call goes unanswered, the
retry counter resets on every poll, and the escalation report ADR 0024 had just built cannot see it: that report is
a query over `unanswered_since`, and this transfer has no silence. The test asserting that a person may resolve
such a transfer said so in a comment: it is in no report, the driver polls it for ever, and a person could only
find it by asking about a movement nobody had told them to ask about.

That is the gap this ADR closes. It is not about a rail that is down - that is escalation's subject. It is about a
rail that is up and holding the group's money.

## Decision

**1. A submission has an age, and the age is a stored instant.** `V510` adds
`settlement_transfers.submitted_at`, written by `markTransfer` when the rail answers that it has the transfer. The
write is `coalesce(submitted_at, :at)`, which is the whole point: a later poll that answers "still pending" goes
through the same method, and an age measured from the most recent poll would restart every fifteen minutes. A
transfer the rail confirmed on the first call keeps a null - it was never in flight - and a row that was already
`submitted` when the column arrived is deliberately not backfilled, because the plan's `created_at` is not when a
rail took the instruction; it is stamped on the next poll, and reported by its silence until then. That is why
there is no CHECK tying `submitted` to a non-null instant: the only row that cannot satisfy it is one that predates
the column, and a migration's job is not to guess.

**2. The threshold is its own policy, with its own number.** `Reconciliation` is a second record beside
`Escalation`, configured as `reductxn.settlement.reconcile.after` and defaulting to one hour. Separate records
rather than one with two durations, and separate numbers rather than one shared: a rail that says nothing and a
rail that says "still pending" are different failures, and a deployment's tolerance for them is not the same figure
- an hour at the rail is long enough that a confirmation queue is not the explanation, while an hour of silence
from a busy rail is not yet news. Merging them into one type would also put two beans of the same class in the
Spring context, which resolves by parameter name and so breaks at runtime rather than at compile time.

**3. One report, two reasons.** `StuckTransfer` replaces `SilentTransfer` and carries a `Reason`:
`NO_CONFIRMATION` when the transfer is at the rail, `NO_ANSWER` when it never got there and the rail is not
answering. `ReadStuckTransfers.stuckLongerThan(unansweredCutOff, unconfirmedCutOff, limit)` is one query with one
predicate - `(status = 'submitted' AND submitted_at <= unconfirmedCutOff) OR (unanswered_since <= unansweredCutOff)`
- so the listing an operator reads holds everything no rail has decided, with the reason and the age on each line.
The row type's name changed because it had stopped being true: a report that carries transfers with no silence
cannot call them silent.

**4. The age is measured from where the trouble began.** `StuckTransfer.stuckSince()` is the acceptance when a rail
took the instruction, and the start of the silence otherwise. For a transfer at the rail whose acceptance instant
is unknown it falls back to the silence, which understates the wait rather than overstating it - the honest
direction for a number that tells somebody how long they had to act. The query orders by the same expression, and a
test pins the two together rather than a comment claiming they agree.

**5. Nothing else changes.** The report is still a read: no state is written, no transfer is decided, and the
cadence logs it every pass (`--reductxn.command=escalations` prints the same listing on demand). The command name
stays `escalations`, because an operator's muscle memory is a surface too and both halves are section 4.4's
machinery; what changed is that each line says which problem it is. ADR 0025's resolution applies to both reasons
unchanged - a person who asks the rail can resolve a `submitted` transfer, which is exactly the movement the report
now tells them to ask about.

## Consequences

- **The hole ADR 0025 documented is closed.** A transfer the rail accepted and will not confirm now appears in the
  report a person reads, with the reason and the age, so the resolution command has something that tells somebody
  which movement to ask about. Before this, the only way to find one was to suspect it.
- **`submitted_at` is a fact about the rail's behaviour, not a lifecycle stamp.** A transfer confirmed on the first
  call has none and never will; a transfer that was at the rail keeps its acceptance after it is resolved, because
  it is when the wait began. A null therefore means "no rail ever took this instruction", which is the same
  distinction that makes `authorized` and `submitted` different problems in the first place.
- **The listing and its total cannot disagree by construction.** Both go through one private predicate
  (`JooqRuns.stuck`), where the old count and list each spelled out their own WHERE clause - the pair an operator
  decides with, and the pair a two-branch condition would have let drift.
- **The rename is churn, and it was paid rather than avoided.** `SilentTransfer` appeared in six classes, four test
  classes and four documents. Keeping the name while adding transfers that have no silence would have left a type
  whose name and invariants promised something the report no longer delivers - the failure mode this repository
  keeps finding, where a document and its code disagree while both read as correct.
- **Nothing resolves anything, and the name "sweep" is doing less work than it sounds like.** Section 4.4's letter
  says a sweep *polls*; the polling was already the driver's job, and what this adds is the threshold and the
  report. The worker takes no action on a stale submission beyond asking about it again, because every action it
  could take - failing it, confirming it, giving up on it - is an assertion about money that only the rail, or a
  person who has asked the rail, can make (ADR 0024, ADR 0025).
- **The log line is longer.** Two lines per stuck transfer now: the reason, amount, run and age, then the rail
  reference, the transfer id and whether the rail is answering. That is deliberate - the rail reference is what a
  support desk asks for and the transfer id is what `resolve` needs - and the count in the summary line is
  unchanged, so a broken rail still reads as one line plus a bounded listing.

## Alternatives considered - and why rejected

**A separate `reconciliations` command and report.** The cleanest thing to *name*, and wrong for the person using
it: two listings for one question means remembering to run both, and an operator who checks one of them will
eventually decide a settlement is fine while the other half is stuck. They are two reasons in one report instead.

**One policy with two durations.** `Escalation(unanswered, unconfirmed)` reads compactly and couples two unrelated
deployment decisions - the numbers are set by different people for different reasons, and a record that holds both
means changing one is a change to the other's type. It would also make every caller pass a number it does not use.

**One shared policy type with two beans.** Tempting, since both are "a duration and a cut-off" - and it puts two
beans of one class in the Spring context, which is resolved by parameter name. A rename would then break the wiring
at runtime instead of at compile time, which is exactly the kind of failure this codebase writes ADRs to avoid.

**Measure the age from the most recent poll.** The cheapest measure, since the write is already happening - and it
is the measure that hid the problem: a rail answering "still pending" every fifteen minutes would reset the clock
every fifteen minutes, so a transfer at the rail for a week would always look like it had just been submitted.

**Backfill `submitted_at` from `created_at`.** A migration that leaves no nulls, and a lie: `created_at` is when the
group's cycle was planned, not when a rail took the instruction, and the column exists only to be measured against
a threshold. A row that predates the column is stamped on the next poll instead, which is one pass away.

**A CHECK constraint tying `submitted` to a non-null instant.** The shape a careful reviewer would ask for, and
unsatisfiable by exactly the rows it would be added for. Leaving it out is a decision with a reason, recorded here
and in `V510`, rather than an omission.

**Let the sweep act: fail a stale submission, or stop polling it.** Failing it asserts that money did not move,
which is the assertion ADR 0024 refused to make on the strength of silence - and here it would be made on the
strength of an answer that said the rail *has* the money. Stopping the polls would abandon the only cheap way to
learn what became of it. So the sweep's output is attention, and the action is a person's.

**Keep `SilentTransfer` and add a nullable `submittedAt`.** No rename, no churn, and a type called silent carrying
transfers with no silence - with its constructor's invariant ("a resolved transfer has been decided by a rail that
answered") quietly wrong for the new case.

**Two queries unioned in Java.** What the report needs is one ordered, limited list plus a total. Two sets merged
in the application would mean sorting and paginating in the caller, and a total that is the sum of two counts -
three places for the halves to disagree about what "stuck" means.
