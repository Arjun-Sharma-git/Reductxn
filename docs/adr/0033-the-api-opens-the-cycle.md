# ADR 0033 - The API opens the cycle; the worker still computes it

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`POST /v1/groups/{groupId}/cycles`, `OpenCycle`, `Cycles`, `JooqCycles`,
  `GroupCyclesController`, `CycleRequest`, `SettlementCycleResponse`), the contract's cycle operation and the
  `SettlementCycle` schema, the regenerated client types
- **Amends:** ADR 0032 - its closing note guessed the shape of this slice and guessed wrong, for a reason worth
  keeping (below)
- **Related:** ADR 0020 (the API records, the run judges), ADR 0021 (a read model does not recompute judgements),
  ADR 0032 (a cycle and a run are two acts); RULE B6, B16, B17, B20, B21

## Context

ADR 0032 gave the worker the mechanism a settlement policy needs - a cycle can be opened without being computed,
and the cadence computes what was opened - and it left one hole, named in its consequences: **an operator with a
shell could open a cycle, and nobody else could.** A member reading the plan (`GET /groups/{groupId}/runs`), and a
member deciding it (`POST /runs/{runId}/decisions`), both existed; a member *starting* it did not.

The contract had described the operation all along, and implementing it as written was impossible for three
reasons, all of which ADR 0032 recorded rather than fixed:

- `POST /groups/{groupId}/cycles` took `{action: open|close, periodStart, periodEnd}` - `open` needs the *instant*
  that keys a cycle and the body carried dates, `close` has nowhere to be written because `settlement_cycles` has no
  status column at all, and the `201` had no body, so a client could not learn the id that
  `POST /cycles/{cycleId}/runs` needed.
- `POST /cycles/{cycleId}/runs` became redundant the moment a cadence computed an opened cycle: a client that knows
  a cycle's id has nothing left to ask for.

ADR 0032 then guessed how the write would cross the boundary, and it guessed the *opposite* of what this slice
does:

> Either the API records a request in its own table which the worker's pass consumes ... or the API writes the cycle
> row itself, which is ADR 0020's crossing pattern and would duplicate an *identity derivation* rather than a
> column list. ... the first is what `V501`'s comment hinted at and is the shape this ADR would choose.

**That guess was wrong, and checking it was the first thing this slice did.** The premise was that an API-written
cycle would have to derive `nameUUIDFromBytes(group + ":cycle:" + asOf)` for itself, which is a key scheme copied
across a deployable boundary. It does not: `JooqRuns.open` inserts the worker's proposed id with
`on conflict (group_id, as_of) do nothing` and *then reads back whichever row the pair actually has*
(`select id from settlement_cycles where group_id = ? and as_of = ?`), writing the run against **that** id.
`OpenCycleTest.aCycleOpenedNowIsTheCycleComputedLater` had already proven it - the test's fixture mints a random
id, and the run comes back over it. So an id from the API and a derived id from the worker are both accepted, and
the derivation is a *proposal* the database is free to override. The request table, its consumer, its lifecycle and
its rows that would grow for ever were all unnecessary.

The second thing this slice decided was what the endpoint may *refuse*, and the answer is nothing:

- **Refusing "nothing to settle" is wrong, not merely duplicative.** The group's commitments are this module's own
  rows, so the check is easy here and looks careful - and it would refuse a request the worker satisfies a minute
  later, because a commitment recorded tomorrow can carry an `incurred_at` inside this cycle. A backdated bill is
  the ordinary case; ADR 0032 says so in the words that also justify the pass looking at a refused cycle again.
- **Refusing an instant in the future is wrong too.** The worker resolves every rate, tax rule and commitment at
  `asOf` rather than at now, so a cycle for the end of the month is a legitimate "settle what is recorded by then"
  - and it is exactly what a settlement policy would open.

## Decision

**1. The API writes the cycle row, and mints its own id.** `POST /v1/groups/{groupId}/cycles` writes
`settlement_cycles` through `JooqCycles`, exactly as `POST /runs/{runId}/decisions` writes `settlement_decisions`
through `JooqDecisions` (ADR 0020). The crossing costs what it cost there and is defended the same way: four
columns written down in one adapter, the schema still assigned to `workers/settlement` by the owner map, and tests
on both sides of it - `OpenCycleApiTest` asserts the row it wrote, `ComputeOpenedCyclesTest` writes the same four
columns with the same SQL and asserts the cadence turns them into a plan.

**2. No id derivation crosses the boundary, because the database arbitrates the pair.** The API mints a random
uuid; the worker proposes `nameUUIDFromBytes(group + ":cycle:" + asOf)`; whoever inserts first wins, and both paths
read the stored row back. What makes a cycle unique is `UNIQUE (group_id, as_of)` - a constraint rather than a
convention two deployables have to keep in step, where drift would be a cycle nothing ever computes rather than a
failure.

**3. The instant is required and the response carries the cycle.** A default of "now" would make two requests a
microsecond apart two different cycles, so a double click would settle a group twice and a retry after a timeout
would open a second cycle; with the instant supplied, opening is idempotent on the pair. `201` when this request
opened the cycle, `200` when it was already open, and both carry the cycle - the distinction is the *store's*
answer, so no check-then-insert happens before the write, and the client learns the id either way.

**4. The operation refuses nothing, and the contract now says so.** The two plausible refusals are both wrong
(a backdated commitment, a future instant), so there is no `422` on this path at all. The only `400` is a malformed
request - a missing instant, a group id that is not a uuid - and it reuses `api.request_incomplete`, because a code
identifies a condition rather than an endpoint (RULE B20).

**5. `close` and `POST /cycles/{cycleId}/runs` are removed from the contract rather than left unimplemented.**
`settlement_cycles` has no status column, so "close" has nowhere to be written; the cadence is the trigger, so a
"compute this cycle now" operation has nothing to ask for. A contract that offers an operation its own schema cannot
express is one that will be implemented as written one day, and both removals are recorded in
`docs/specs/settlement.md` instead of being quietly dropped.

**6. The response schema is the row: `id`, `groupId`, `asOf`, `createdAt`.** No status, no run id. Whether a cycle
has been computed is a question about a *run*, and the group's runs answer it; a `status` field here would be this
deployable's guess at another module's lifecycle, and it would be wrong in the window between a cycle being opened
and a plan existing - which is precisely the window a client is watching. `createdAt` is what a client shows as
"waiting since", which is the honest way to report a cycle the worker has not computed.

**7. The client's types were regenerated in the same change.** The contract is the client's source of truth and the
drift gate (RULE C3) compares the committed generated file against it, so an amendment that skipped regeneration
would be a red build rather than a review comment.


## Consequences

- **The gap ADR 0032 narrowed is now closed for the member as well as for the operator.** A cycle can be opened
  from a client (`POST /v1/groups/{groupId}/cycles`), and within a minute the worker has computed a plan for it,
  which is the run the member then reads and answers. The whole flow - ask, read, decide - is available over HTTP;
  the client screen for the first step is the next slice, and the endpoint is what it needs.
- **What is still open, and where it would be visible.** There is no `GET /groups/{groupId}/cycles`, so a member who
  has asked for a cycle can only see the *result* of asking - the run in their runs list - and a cycle that the
  worker refuses (`settlement.nothing_to_settle`, because the group has nothing recorded at or before the instant)
  is invisible until that read exists. The read is the next slice's other half, and `createdAt` is already in the
  response so a client can say "waiting since" rather than "nothing happened".
- **The three contract gaps ADR 0032 recorded are closed, two by removal.** The instant the operation needs is in
  the body, `close` is gone because nothing can store it, and the `201` carries the cycle so the client can learn
  the id that `POST /cycles/{cycleId}/runs` used to need - and that operation is gone too, because a cadence
  computing an opened cycle left it nothing to ask for. `docs/specs/settlement.md` records both removals where it
  used to record the gaps.
- **Seven tests, and one of them asserts an absence.** `OpenCycleApiTest` covers the row the worker reads
  (the four columns, and the instant being the client's rather than the clock's), opening twice being `200` with the
  first request's id and instant, one instant in two groups being two cycles, a request without an instant being a
  coded `400` that writes nothing, a malformed group id being the same code - and a cycle opened for an instant with
  nothing recorded at all being *accepted*, which is the decision this ADR took rather than an omission.
- **ADR 0032's guess is left standing, with an amendment.** It said a request table was the shape it would choose,
  on the premise that the API would have to duplicate the id derivation. The premise was false and the cost of
  checking it was one read of `JooqRuns.open`. The general lesson is a cheap habit rather than a deep one: when a
  decision's reasoning turns on a property of an existing adapter, read the adapter before designing around it - and
  when a test already proves the property (`OpenCycleTest` did), read the test as the evidence it is.
- **Identity is still client-supplied.** The group in the path is whoever the client says, exactly as an
  obligation's debtor is; a client can open a cycle for any group id it can type. This ADR does not pretend
  otherwise, and the identity slice changes that boundary rather than this endpoint.

## Alternatives considered - and why rejected

**A request table in `apps/api`'s schema, consumed by the worker's pass.** ADR 0032's own preference, and the
textbook answer when two deployables share a database. Rejected on the evidence: the crossing it avoids does not
exist (the pair arbitrates, not the id), so the cost is a second table, a consumer, a lifecycle for the request rows,
and rows that grow for ever - all to avoid writing four columns into a table this module already reads.

**Refusing at request time when the group has nothing to settle.** Refused in the Context: it is the wrong moment to
ask, because the answer can change (a backdated commitment), and it would put a settlement judgement in the module
that does not own the run - ADR 0020's restraint, applied to a new endpoint.

**Implementing `POST /cycles/{cycleId}/runs` as a no-op that returns the eventual run.** A lie told politely: the
operation is asynchronous and the API cannot compute, so the honest implementation is a `202` with a body the server
has not got. Removing it is the alternative that keeps the contract truthful.

**Keeping `action: open|close` and answering `400` for `close`.** An operation the contract advertises and the
server refuses for every caller is worse than an operation that does not exist, and it invites a client to build the
button.

