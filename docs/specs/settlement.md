# Spec - Settlement lifecycle

**Owner:** backend reviewer · **Module:** `workers/settlement` · **Related:** ADR 0001, ADR 0008

## 1. Lifecycle

```
cycle:     open --> closing --> closed
run:       pending --> computing --> awaiting_approval --> executing --> done
                                              |                 |
                                              +-> rejected      +-> failed (partial)
                                              |
                                              +-> awaiting_fx_funding --> executing
transfer:  pending --> authorized --> submitted --> confirmed
                                  \-> failed
```

A run is created per `(group, cycle)` and contains **one currency partition per currency present in
the cycle**. Computing is asynchronous; the API returns `202 Accepted` with a run id, never a blocking
response. Approval is granted once for the whole run, not per currency, because the group is approving
one settlement of one set of commitments.

`awaiting_fx_funding` is reached when any currency partition leaves a non-zero `fx_clearing` position
(section 5). It is a real, blocking state, not a warning.

**All eight states are reachable as of the execution slice.** `pending` and `computing` are written while a
run is opened and its plan recorded, in one transaction, so neither is observable from outside it - they
exist so that a run interrupted mid-computation is identifiable rather than absent. The transitions between
the rest are performed by the use cases rather than by the states themselves:

| Transition | Performed by |
|---|---|
| `awaiting_approval` or `awaiting_fx_funding` with quorum met and funding covered | `ExecuteRun.execute` - it writes `executing` itself, so a group that has approved everything is still looking at a run that has moved nothing |
| `awaiting_fx_funding --> awaiting_approval` | `RecordFxFunding.fund`, once every currency the plan needs is covered |
| `awaiting_approval --> rejected` | `DecideOnRun.decide` with a rejection |
| `executing --> done` | `ExecuteRun.execute`, when every transfer is confirmed |
| `executing --> failed` | `ExecuteRun.execute`, when any transfer was refused terminally (section 4) |

**Settling is terminal.** A run records the commitments it nets when it is computed, and a run that reaches
`done` has discharged them: neither reader's "open" obligations include them again, whatever instant is asked
about. A `failed` run settles nothing - its money has partly moved and its commitments stay open, so the group
can settle them again once the cause is dealt with (section 4, RULE S3).

## 2. Quorum before real money moves

**RULE S1** - a run may only execute after quorum approval. Default policy: majority of members,
**and every member who owes a net amount must confirm personally**. One admin cannot move the
group's money.

Three details the sentence leaves open, and how they are decided here (ADR 0016 records the first):

- **Who the members are.** The participants in the run's own plan, minus `fx_clearing`. There is no
  `group_members` table yet - groups and membership are `apps/api`'s and no migration creates them - so a
  quorum "of the group's members" would be a quorum over nothing, which passes. The people whose money a
  run moves are stored with the run and are exactly the people it is fair to ask. **This changes when
  membership exists**, and the change is a decision rather than an edit.
- **The clearing account is a participant and not a member.** It is netted like one so that a
  cross-currency cycle comes out right (ADR 0012 point 3), but it is not a person: a quorum it could help
  reach would be the group's own account agreeing on its behalf, in the runs with the largest amounts.
- **A decision is final, and a rejection ends the run.** A member who has answered is not asked again -
  otherwise "what does a withdrawal of consent do to the approvals that came after it" becomes a question
  the product has to answer - and one rejection is enough, because the group is approving one settlement of
  one set of commitments rather than a per-currency series of them. Nothing has moved when a run is
  rejected: a rejection stops a settlement, it does not undo one.

## 3. Idempotency

**RULE S2** - execution is idempotent end to end:

- Each transfer carries a client-generated `rail_ref` derived from `(run_id, from, to, amount)`.
- Re-submitting the same transfer is a no-op returning the existing rail reference.
- Replaying an entire run must not change state. Verified by a Testcontainers replay test.

**Implemented, and the first bullet is where the spec's tuple was incomplete.** The reference is derived
from `(run_id, currency, from, to, amount)`: within one run, INR 100 and JPY 100 between the same pair
produce the same three values while being two different movements. The derivation is stored with each
transfer, so the reference a second attempt computes is the same string, and the schema's
`UNIQUE (rail_ref)` is the half a database can hold - the second insert cannot happen.

The replay test exists and asserts the property at the rail rather than in the worker's own rows: after a
second `ExecuteRun.execute` on a finished run, the run is still `done`, nothing new was submitted, and
nothing was polled. That matters because the worker's rows agreeing with each other would prove nothing if
both were wrong in the same way. The same property is asserted of the funding path (`settlement_fx_fundings`
is keyed by the caller's `funding_ref`) and of `MockRail` itself, which answers a re-submission with the
state it already holds.

**And it holds at the ledger**, which is where a second movement would be recorded: a confirmed transfer's
entry id is derived from the transfer, so a replay books nothing - the second attempt collides on the primary
key instead of posting the movement twice. `SettlementBookkeepingTest` asserts the entry count is unchanged
after a replay rather than trusting the worker's own rows to say so.

## 4. Rail interaction

Execution flows through `libs/rails`. Required behaviours of every adapter:

1. Submit is idempotent on `rail_ref`.
2. Every state transition is persisted before the next call is made.
3. Failures are classified: retryable (network, 429, 5xx) vs terminal (insufficient funds,
   rejected). Only retryable failures are retried, with exponential backoff and a circuit breaker.
4. A reconciliation sweep polls for transfers stuck in `submitted` beyond a threshold.

**Status: 1 and 2 are implemented; 3 and 4 are named gaps.**

**1 and 2.** The port's contract says submit is idempotent, and `MockRail` implements it as a memory:
it answers a re-submission with the state it already holds rather than confirming a movement twice. The
worker's side is stricter - a resolved transfer is never offered again, and a `submitted` one is *polled*
rather than re-submitted, because the question that state poses is whether the money moved. On 2, each
write is its own committed transaction in `ExecuteRunService` (`TransactionTemplate` around one statement)
and it happens before the call it describes: a transfer is written `authorized` before the rail is asked and
its answer is written after. A `@Transactional` method would have been the shorter code and the wrong one -
it would hold a database transaction open across every network call, and a rollback after a confirmation
would erase the only record that money moved.

**3 is not implemented, and the port cannot express it yet.** `PaymentRail.submit` returns
`Result<RailReference>`, which says *that* the rail refused and not whether the refusal was retryable -
there is nowhere in the contract to put that classification, and the spec asks for it. What the worker does
in the meantime is fail in the safe direction rather than the clever one: a call that did not complete
leaves the transfer `authorized` and the run `executing` (nothing was refused and nothing moved), a
transfer refused at the domain level is terminal (the run is `failed`), and re-running the pass retries the
former. Backoff, a circuit breaker and a classification on the port are the next rail slice.

**4 is not implemented.** A `submitted` transfer is resolved by a later pass polling it, which is the same
logic a sweep would run on a timer; what is missing is the timer, the threshold and the job. `V503`
deliberately does not add a `submitted_at` column for it yet - a column nothing reads is a claim rather than
a fact.

## 4a. What execution writes to the ledger

Each transfer that a rail confirms is booked as one `settlement` entry - payer debited, payee credited, in the
transfer's currency - and the entry commits in the same transaction as the transfer's status. The consequences
worth stating:

- **A partial failure books what moved and nothing else** (RULE S3). There is no compensating entry, because
  there is nothing to compensate: the money that did not move has no posting.
- **A replay books nothing**, because the entry id is derived from the transfer.
- **`fx_clearing` ends a cycle at zero per currency** (L8): the commitment booked the group's currency gap into
  the account and the settlement's legs pay it back. What the group brings in from outside is not in the ledger
  at all, because reductxn never held it (P2) - L8's other branch, a run flagged `externally_funded`, is the
  rail-performed-FX case.
- **Nothing is posted for a funding.** A funding is recorded as a fact about the run; the entry that would show
  the group's own contribution arriving needs an account for the group (`owner_type = 'group'`), and the ledger
  spec's account-typing question is open (section 8 records it as this area's remaining gap).

The booking *shape* is `libs/booking`'s (RULE B22, `Bookings.settlement`), so the entry a run writes and the
entry the API writes when an obligation is recorded cannot disagree about what an entry is. The *append* is this
worker's: it is the only process that knows a movement happened. ADR 0017 records why.

**RULE S3** - reduction partially applied. If some transfers confirm and others fail, the run is
`failed (partial)`, net positions are **not** marked settled, and the shortfall is surfaced to the
group as a credit-risk event. There is no silent rollback of confirmed money movement.

## 5. Cross-currency runs and FX funding (ADR 0012)

**Ownership split.** The *plan* - partitions, per-currency positions and transfers, the `fx_clearing`
position and the external requirement - is computed by `libs/planning` (MASTER-PLAN rule B21),
because both this worker and `apps/api` need it and they may not depend on each other (B6). This
worker owns the *run*: lifecycle, quorum, idempotency, rail submission and funding state. Nothing in
`libs/planning` reads a clock or a provider, so the same cycle always produces the same plan.

Each currency partition is netted independently, so the run carries **per-currency** net positions,
transfers, tax and transfer counts. There is no single global total, because adding yen to rupees is
not addition.

**`fx_clearing` is a participant, not a footnote.** A cross-currency obligation is planned as two legs
(ADR 0012 point 3), one in each book, with `fx_clearing` as the counterparty. It is netted like any
other participant so the transfers come out correct, then reported separately: transfers between two
members are member transfers, and any transfer touching `fx_clearing` is part of the group's external
requirement. Lumping the two together would present a group's FX exposure as if it were a member's
debt.

**RULE S4** - a run may not execute while any currency's `fx_clearing` position is non-zero and
unfunded. The engine cannot produce currency it does not hold (P2), so when a partition leaves a
residual clearing position the run transitions to `awaiting_fx_funding` and the group is asked to
supply it. Two ways to satisfy the requirement:

1. **Group-funded.** A member, or the group, brings the currency in from outside and the run is then
   funded. The requirement is shown as an exact amount per currency.
2. **Rail-funded.** The rail declares FX capability (`docs/specs/fx.md` section 9), performs the
   conversion, and the entries are marked `rail_performed_fx = true` with the rail's rate recorded.

**RULE S5** - the FX requirement is a reported number, never a hidden one. The "who pays whom" report
shows, per currency: member transfers, the `fx_clearing` leg, and the outstanding external requirement
if any. A group that cannot fund it is told plainly rather than shown a settled state it did not
reach.

## 6. Default handling

Netting concentrates obligation into fewer, larger transfers, which means a single default can
leave the group short. Mitigations, in order of preference:

1. Per-member credit limits enforced at obligation-recording time.
2. Optional pre-authorisation or hold at the rail.
3. Group-level reserve.
4. Explicit default policy agreed by the group, displayed in the UI.

**This is a product surface, not a footnote.**

## 7. Open questions

- Cycle frequency: fixed monthly vs per-group configured vs manual close.
- Whether the approval window expires and re-computes.
- How a group funds an external FX requirement operationally, and who is permitted to do it.
- Whether `awaiting_fx_funding` should expire and re-compute, since rates move while a group waits.
- Whether per-currency partitions may be executed independently once approved, which would let a
## 8. Implementation status

**Implemented in `workers/settlement`, tested against a container (53 tests across seven classes):** opening a
run for a `(group, as-of)` pair, computing the plan through `libs/planning`, and recording it as rows - one
partition per currency, one net position per participant per currency, one transfer per movement with its RULE
S2 rail reference, and the identities of the commitments it nets. The run's status is decided in the use case,
not the adapter: `awaiting_approval`, or
`awaiting_fx_funding` when any currency's `fx_clearing` position is non-zero (RULE S4).

On top of that, the run's whole lifecycle:

- **Quorum (S1)** - `DecideOnRun` records a member's answer and `Quorum` decides what it means: a majority of the
  run's own participants, and every net debtor personally. The decision is a pure function over stored rows,
  tested without a database, with the container tests checking that the right rows reach it.
- **Funding (S4)** - `RecordFxFunding` records currency arriving from outside and moves a run from
  `awaiting_fx_funding` to `awaiting_approval` once every currency the plan needs is covered. Partial arrivals
  are recorded and the shortfall reported; the requirement is per currency and never summed.
- **Execution (S1-S4)** - `ExecuteRun` checks every gate before it moves anything, writes `executing`, and walks
  the transfers: resolved ones are skipped, submitted ones are polled, the rest are written `authorized` and
  offered to the rail. The run is `done` when every transfer is confirmed, `failed` when one was refused
  terminally - with the confirmed ones left confirmed - and `executing` while the rail has not answered.
- **A rail** - `libs/rails` has its first adapter, `MockRail`, reached through `Rails.mock()` and wired by
  configuration (`reductxn.rail.name`), which refuses an unknown name at startup rather than falling back. It
  moves nothing, confirms what it accepts, and is the deployment this project has.
- **The ledger** - every confirmed transfer is booked as a `settlement` entry (section 4a), so a settled cycle
  leaves every member flat and `fx_clearing` at zero per currency. The commitment an obligation recorded and the
  entry a run writes are the two halves of one verifiable chain, and the tests hand the rebuilt entries to
  `EntryChain` rather than comparing fields. Section 4a has what this does and does not cover.

The tests verify the *stored* plan and stored state rather than the call: per-currency zero-sum read back from
`net_positions` (N1), every participant zeroed by applying the plan's own transfers (N2), the clearing account's
residual, that a retry returns the same run and writes no second plan, and - on the execution side - that the
rail was asked exactly once per transfer, that a replay asks it for nothing, and that a run without quorum or
funding never reaches it at all.

**Not implemented, and each for a stated reason:**

| Missing | Why it is not here |
|---|---|
| Posting the currency a group brings in from outside | A funding is recorded as a fact about the run and moves no ledger entry, so nothing shows the group's own contribution arriving. The entry needs an account for the group itself, and what kind of account that is (`owner_type = 'group'`, and which of asset/liability/income) is open in `docs/specs/ledger.md` section 6 - guessing it here would have invented typing the ledger has deliberately not chosen |
| A scheduler, or any other trigger | The tests call the use cases. Nothing computes a run, moves an approved one on, or resolves a transfer left in flight without a caller - which is the honest form of "nothing schedules anything" |
| Retryable-vs-terminal classification, backoff, circuit breaker (section 4.3) | `PaymentRail.submit` returns a `Result` with nowhere to put the classification the spec requires, so the port needs a change before the behaviour can exist. The worker fails safe meanwhile: an unreachable rail leaves the transfer `authorized` and the run `executing`, and a later pass offers it again |
| Reconciliation sweep (section 4.4) | Needs a submitted transfer to reconcile against. A later `execute` polls one today, so the logic exists and the job, the threshold and the timer do not |
| Cycle frequency and closing (section 7) | A cycle is keyed by `(group, as-of)`: an operator or a scheduler chooses the instant. Whether cycles close on a schedule, per group, or manually is open, and the current shape forecloses none of the three |
| Per-currency execution (section 7) | Would let a funded currency settle while another waits. Tempting for latency and risky for consistency, and the question stays open - `ExecuteRun` refuses the whole run if any currency is unfunded, or if the rail cannot settle it |
| The "who pays whom" report a group reads | The rows it needs exist: transfers with their states and the requirement per currency. The report is a client-facing surface and the worker has none, so it is `apps/api`'s read model over these tables - RULE S5's remaining half, named as such in `RuleCoverageTest` |

**Deliberately not this worker's:** the plan itself (RULE B21, `libs/planning`), the entries a run books when it
executes (RULE B22, `libs/booking`), and anything a client reads. The worker has no public surface, so a run's
report reaches a group through `apps/api`'s read model over these tables - which is RULE B17's arrangement and
the reason the API and this module never depend on each other (RULE B6).
