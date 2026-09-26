# ADR 0017 - A settled run books its movements, and settling is terminal

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `libs/booking` (`SettledTransfer`, `Bookings.settlement`); `workers/settlement`
  (`JournalEntries`, `ChainHeads` and their jOOQ adapters, `ExecuteRunService.record`,
  `settlement_run_obligations` in `V504`, the "already settled" exclusion in both open-obligation read models);
  branch `feat/settlement-posting`
- **Related:** RULE B6, B17, B22; RULE S2, S3; invariants L1-L9; ADR 0004, 0012; settlement spec sections 3, 4, 8

## Context

Execution landed and moved money at a rail, and two things were then plainly untrue of the system:

1. **The ledger did not know.** A run reached `done`, the rail had confirmed every transfer, and the members'
   positions in the ledger were exactly what they had been before - the obligations still outstanding. L5 says a
   balance is a projection over postings, so the ledger was not merely incomplete, it was wrong: it showed a
   group still owing what the group had paid.
2. **The next cycle would settle the same commitments again.** A run nets everything open at its instant, and
   nothing marked an obligation as discharged, so a second cycle over a later instant would net the same debts
   and move the group's money a second time for one promise. This is the more dangerous of the two, because it
   is silent until it happens twice.

Both are boundary questions rather than omissions: the ledger's tables belong to `libs/ledger` and are written
by `apps/api`; `obligations` belongs to `apps/api`; and `apps/api` and `workers/settlement` may not depend on
each other (RULE B6). So the interesting part of this decision is *who writes what*, and the answer has to be
consistent with the module graph rather than convenient for the code.

## Decision

**1. The run that moved the money writes the entries for it.** `libs/booking` gains a third shape -
`Bookings.settlement(ChainHead, SettledTransfer)` - and the worker appends it through its own `JournalEntries`
port when, and only when, a rail confirms a transfer.

The booking *shape* stays in `libs/booking` (RULE B22) so that two writers of one ledger cannot disagree about
what an entry is; the *append* is the worker's, because the worker is the only process that knows a movement
happened. RULE B17 is about which module's migration creates a table, not about who may insert a row: the schema
is `libs/ledger`'s and both deployables write to it, exactly as both write `journal_entries` today for
obligations. The alternative - publishing an event for `apps/api` to book - would make the recording of a
settlement depend on another deployable's availability, and there is no event infrastructure to carry it.

**2. Settling is terminal, and it is derived from the run rather than written onto the obligation.**
`settlement_run_obligations` records which commitments a run nets, written with the plan in one transaction. An
obligation is open unless a run with status `done` lists it. Both open-obligation read models - the worker's
compute and the API's preview - apply the same rule, so a group previews what the run would settle.

Three consequences follow, and each is a decision rather than a detail:

- **A `failed` run settles nothing.** Its money has partly moved and its commitments stay open, so the group can
  settle them again once the cause is dealt with. This is RULE S3 in the spec's own words ("net positions are
  not marked settled"), and it is why "settled" is the run's status rather than a flag copied onto the
  obligation.
- **An entry is written per transfer, not per run.** A settlement is not one big entry: each movement has its
  own rail reference, and a partially applied run's entries are exactly the movements that happened. "No silent
  rollback" then needs no compensating logic - there is nothing to roll back, because nothing was written for
  the money that did not move.
- **Settling is a fact about the past, not about an instant.** An obligation a `done` run has settled is never
  open again, whatever instant a reader asks about. Deriving it from instants instead ("a run at or after
  `incurred_at`") is wrong for a backdated obligation, and `V504` sets that argument out in full.

**3. The status and the entry commit together.** `ExecuteRunService` writes a confirmation and its ledger entry
in one transaction. A transfer reading `confirmed` with no entry behind it would report money moved that the
ledger never saw. This is the one place where the per-write transactions execution uses would be wrong.

## Consequences

- **The ledger is right after a settlement.** Every member the plan touched is flat in each currency, and
  `fx_clearing` is zero per currency - which is L8's end-of-cycle claim, now asserted against the stored
  postings rather than computed from the plan.
- **The double settlement is impossible**, and it is impossible in both readers rather than in the one that
  might be asked next: a later cycle is refused with `settlement.nothing_to_settle`.
- **`apps/api`'s test database needs the worker's migrations.** Its preview reads the worker's tables and it may
  not depend on the worker, so those migrations arrive by filesystem path - the exact mirror of what the
  worker's tests already do with `V101__obligations.sql`. Recorded as a finding, because a test fixture and a
  deployment now have to agree about a schema that no single module owns.
- **The worker is the second writer of `journal_entries` and `postings`**, and the second implementation of the
  chain-head read. Duplicated code with a named risk: two copies of a lock-backed query can drift, and a missing
  lock fails under load rather than in review.

## What becomes harder

- **Nothing is posted for the currency a group brings in from outside.** A funding is recorded as a fact about
  the run and moves no ledger entry, so the group has no account showing what it contributed. Posting it needs
  an account for the group itself (`owner_type = 'group'`), and the ledger spec's section 6 has the account
  typing question open - deciding it inside this slice would have guessed at it.
- **The plan's provenance is per run, not per transfer.** Which commitment caused which transfer is not
  recorded, so a member can be told "this run settles the cycle's commitments" and not "your payment settles
  Alice's loan". The netting engine discards that mapping (N2's whole point is that it need not exist), and
  reconstructing it would need a different engine output.
- **A settled obligation carries no timestamp of its own.** "When was this settled" is "when did the run
  finish", which is the run's story rather than a column. Fine while one run settles a commitment, a question
  if that ever changes.

## Alternatives considered - and why rejected

**Have `apps/api` book the settlement, driven by an event or a poll.** It keeps the single-writer property that
`journal_entries` has today, and it costs more than it buys: the API would need an event channel that does not
exist, a settlement would go unrecorded whenever the API was down while the worker was up, and the module that
knows what moved would be telling another module to write it. The rows are the ledger's, and the fact is the
worker's.

**Mark the obligation settled: a `settled_by_run_id` column on `obligations`.** The most direct expression of
"this is discharged", and it puts the worker's write into `apps/api`'s table - the boundary RULE B17 draws for
migrations and the one the read models respect. It also creates a second place where a run's status has to be
kept in step: the column would have to be unset when a run fails, or a partial failure would read as settled.

**Derive "settled" from instants, with no link table.** No extra table and no plumbing of obligation ids through
the planner - and wrong for exactly the case that matters. `incurred_at` is client-supplied (P4) and may be
backdated, so an obligation incurred last Tuesday but recorded today, after a run over last week, would be
derived as settled by a run that never saw it: silently un-settleable, and invisible until a member asks why
their debt is still open.

**Post one entry per run, with the transfers as postings.** Fewer entries, and it breaks the two properties that
matter: a partial failure would need the entry rewritten to reflect what actually moved (L3 forbids that), and
the per-transfer rail reference - the thing an auditor follows back to the instruction - would have nowhere to
live.
