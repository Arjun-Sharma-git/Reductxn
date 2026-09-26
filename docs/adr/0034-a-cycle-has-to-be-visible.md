# ADR 0034 - A cycle has to be visible before it can be waited on

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`GET /v1/groups/{groupId}/cycles`, `ReadGroupCycles`, `CycleReport`, `JooqCycles.listByGroup`),
  the contract's cycle read and `SettlementCycle.runId`, `V511__cycles_read_index.sql`, `apps/web` (the cycles card,
  `entities/cycle`, `useOpenCycle`, `shared/lib/localTime`), and the regenerated client types
- **Related:** ADR 0030 (a run has to be findable before it can be answered - the same argument, this time for a
  cycle), ADR 0032 (a cycle and a run are two acts), ADR 0033 (the API opens the cycle); RULE B16, B17, C3, F8

## Context

ADR 0033 gave a member a way to ask for a settlement, and named its own hole in the same breath: **the request had
no read.** Opening a cycle writes a claim about an instant, a plan over it is the worker's business, and the worker
computes one within a minute - so between asking and the cadence arriving, and forever if the worker refuses to plan
an empty cycle (`settlement.nothing_to_settle`), the API had nothing to show. A member who had asked and a member
whose request could not be honoured saw exactly the same thing: nothing at all.

That is the shape ADR 0030 already answered once, for the other end of the flow: *a run has to be findable before it
can be answered*. This is the same argument one act earlier - **a cycle has to be visible before it can be waited
on** - and it comes with a harder question. A run's state is a word the worker owns (`awaiting_approval`, and the
rest of a `CHECK` constraint). A cycle's is not: whether one has been *computed* is a fact about a run, so any status
this API put on a cycle would be its guess at another module's lifecycle - wrong, in exactly the window a client is
watching it.

Three smaller questions came with it:

- **Which order is "newest first"?** A cycle has two instants: `as_of`, the instant it settles as of, and
  `created_at`, when somebody asked for it. They disagree precisely when a member settles something late - the
  ordinary case - and then only one of the two orders puts the thing they just asked for at the top.
- **What does the client do about the wait?** The worker's cadence is up to a minute, so a page that read once and
  stopped would show "waiting" for a plan that had already been computed, and a page that polled unconditionally
  would be load without a question.

## Decision

**1. The read exists, one page of cycles, most recently asked for first.** `GET /v1/groups/{groupId}/cycles` is
`WHERE group_id = ? ORDER BY created_at DESC, id DESC LIMIT ?`, backed by `settlement_cycles_by_group
(group_id, created_at DESC)` - the index `V511` adds because the contract names it, and because without it the
database sorts a group's whole history to answer the first page. **`created_at`, not `as_of`:** a member who has
just asked for last quarter's settlement wants it at the top, and `as_of` order would bury it halfway down a page of
history. A second consequence is worth stating, since it is why the tie-break is a formality: the pair
`(group_id, as_of)` is unique, so no two of a group's cycles share an instant either.

**2. The row carries `runId`, which is a fact rather than a status.** Present means a plan exists and can be read at
`/runs/{runId}`; absent means there is none. **The absence is deliberately ambiguous**: a cycle asked for a moment
ago and a cycle the worker will never plan (nothing recorded at or before the instant) look the same, because telling
them apart would mean *recording* the refusal - a column, a lifecycle, and a judgement this module does not make. So
the API reports what it knows and the client shows *"asked, waiting since `createdAt`"* - true of both, and a list
ordered by when cycles were asked for turns it into a visible anomaly: a row that has waited for days is a fact a
member can act on, where an invented status would have called it something.

**3. One schema for both endpoints, so the open response resolves the plan too.** `SettlementCycle` is `id`,
`groupId`, `asOf`, `createdAt`, `runId` - the same shape on the list and on `POST`, which costs the write one extra
left join and buys two things: a client never needs a second way to read a cycle, and the `200` answer is truthful -
a member re-asking for an instant that was settled last month is told the plan exists rather than told to wait. A
nullable `runId` is the smallest honest field for that; the alternatives were a `status` vocabulary invented here or a
nested run summary, and both are this deployable describing a run.

**4. The client watches the one thing that is asynchronous by design, and stops when it is not.** The cycles card
polls while any cycle is waiting and stops once none is. Polling always is load without a question; polling never
leaves a member refreshing a screen to find out whether anything happened, which is the behaviour the read exists to
replace.

**5. The wall-clock conversion moved to `shared/lib/localTime`.** A second consumer is the evidence that it was never
a feature's private concern (RULE F8: a feature's internals are private, so the fix is a move rather than an import
across features). The test that comes with the move asserts properties rather than formatted strings, because the
expectation that *does* depend on the reader's zone is the one that failed on the first run of that test at UTC+5:30.

**6. Nothing in the client pre-judges an empty cycle.** The API refuses nothing, for a reason that is stronger in a
browser than it was in the API (ADR 0033): a commitment recorded tomorrow can carry an instant inside a cycle opened
today, so a form that refused an instant with nothing recorded at it would stop a member settling a backdated bill.
The only local refusal is a missing or unreadable instant, and it uses the server's own code
(`api.request_incomplete`) so that one rule has one message wherever it was caught.

- **Where does a wall clock become an instant?** The obligations form already needed that conversion, and it lived
  in `features/obligations/ui/localTime.ts` - private to a feature, and about to be needed by a second one.

## Consequences

- **The flow is walkable in the product, not just over HTTP.** A member can close the group's books at an instant,
  watch the row say "waiting since", and follow the plan when the worker has computed it - and the same screen shows
  the plans that already need an answer. The two acts ADR 0032 separated now have a member-facing path through both
  of them: ask, wait, read, answer.
- **Seventeen new tests, split by what they can see.** Eight in `ReadGroupCyclesApiTest` (an empty list rather than an
  error, a row with no plan, a row with its plan, `created_at` order where it disagrees with `as_of`, one group's
  cycles not being another's, the limit bounded by the contract, a coded `400` for a malformed id, and that a join
  never reports one cycle twice) and two more in `OpenCycleApiTest` (a fresh cycle leaves `runId` out; a cycle that
  already had a plan is reported with it). On the client side: four unit tests for the wall-clock conversion, six for
  the request and the row's meaning, and two browser tests - one that closes the books and sees the row come back,
  one that follows a plan from a cycle whose plan exists.
- **The read is a projection of another module's rows, and that is the familiar cost.** `JooqCycles` reads
  `settlement_runs` to answer "does a plan exist" - the same crossing ADR 0021 documents for the preview and ADR 0030
  for the runs list. The table's owner is unchanged (the worker writes every run), and both sides are tested:
  `ReadGroupCyclesApiTest` writes a run row with the worker's own columns and reads it back through the endpoint.
- **What is still open, and it is now stated where a client hits it.** Cycles cannot be paged beyond a hundred
  (`V511`'s index serves the first page, and a cursor is the next thing a long history needs); a cycle cannot be
  *closed* (no status column, ADR 0033); the refusal a member cannot see yet - the worker declining to plan an empty
  cycle - is visible only as a row that stays waiting; and nothing schedules a cycle, because a schedule is a group's
  policy and groups do not exist yet.
- **A join made every unqualified column ambiguous, and the test caught it.** The first version of the read reused
  the write's field constants, which are unqualified; adding the left join turned `id` into a runtime error the
  compiler could not see. The adapter now has two sets - qualified columns for the reads, unqualified for the insert -
  and a comment saying why, because the next person to add a join to this class will otherwise repeat it.
- **The form's field is hand-rolled, and one file of styles is now duplicated.** The obligations screen's
  `Field` control is private to its feature (RULE F8), and the cycles card needs one label, one input, one hint and
  one refusal - so it has them inline, with three rules copied from `Field.module.css`. The honest fix is to hoist
  that control into `shared/ui` with a refusal prop that is not a feature's type; it is recorded as a follow-up rather
  than done here, because it would change a working screen and its browser test for a slice's worth of benefit.

## Alternatives considered - and why rejected

**Ordering by `as_of`.** Defensible - a cycles list is a list of periods, and periods read newest-first - and wrong
for the moment it matters: a member who has just asked for last quarter's settlement would find their new cycle
halfway down the page, which is the opposite of what a list is for immediately after a write. `created_at` order also
matches the runs list, so two screens in one flow disagree about nothing.

**A `status` field: `waiting`, `computed`, or the run's own status.** The first invents a vocabulary for a fact that
belongs to a run; the second copies the worker's `CHECK` constraint into a second schema, where a ninth state would
have to be added twice. `runId` says the same thing with no vocabulary at all.

**Recording the refusal so the two kinds of waiting could be told apart.** A column on `settlement_cycles` written by
the worker when it declines to plan - rejected because it is a *judgement* about a group's book stored by the module
that owns cycles but not commitments, and because the refusal is not permanent: a commitment recorded tomorrow can
carry an instant inside an older cycle, which the worker's pass already accounts for (ADR 0032).

**A nested run summary in the cycle row.** It would save the client one fetch per computed cycle and it would be the
second description of a run - the drift ADR 0021 refuses for the runs list, arriving through the cycle side.

**A separate screen for cycles, at `/groups/{groupId}/settlement/cycles`.** The card belongs above the runs on the
settlement page: asking for a settlement and reading the plans are one job, and a member who has to navigate to see
whether their request produced anything has been given a second place to look rather than an answer.

**Polling forever, or not at all.** Both are in the Context; the card polls while something is waiting, which is the
only interval where the answer can change.

