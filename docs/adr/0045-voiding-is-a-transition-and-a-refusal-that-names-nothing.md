# ADR 0045 - Voiding is a transition, a refusal that names nothing, and idempotence by state

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `apps/api` (`ObligationRefusals.NoSuchObligation` and `NotOpen`, `VoidObligation`, `ObligationVoids`,
  `VoidObligationService`, `JooqObligationVoids`, the `/obligations/{obligationId}/void` endpoint, two `ExpectedFailures`
  entries) and the contract: the operation described, its `200` given a body, `Idempotency-Key` removed, and
  `400`/`401`/`404`/`409` declared; 5 API tests
- **Related:** ADR 0004 (never delete), ADR 0036 (the derived status), ADR 0042/0043 (the caller from the token, and
  enforcement deferred), ADR 0044 (the ordering discipline this one inherits); RULE B14, B17; P1

## Context

`POST /obligations/{obligationId}/void` had a summary - "compensating entry, never a delete" - an `Idempotency-Key`, a `200`
whose entire description was "Voided", and a `409`. Nothing served it, so `voided_at` existed, the derived status read it
(ADR 0036), and an obligation could be recorded but never corrected.

The plan recorded it as "a transition rather than a new row", which is right, and left three things open: what a `200`
contains, what a `409` means, and who may do it. The third is the interesting one, because every write in this build until
now took the caller from the request body or from nothing at all, and ADR 0042/0043 deliberately deferred enforcement to
"the slice that needs it".

## Decision

**A void is a transition, never a delete** (ADR 0004): `voided_at` is set, the row keeps its instant, and the derived status
becomes `voided`. The group can still see that somebody recorded it and that somebody corrected it.

**The `200` body is the obligation.** "Voided" is not something a client can act on - the screen showing the row needs its
new status, and it already renders this shape. `occurredAt` stays the instant the commitment was incurred: when it was
voided is not part of this schema, because this schema is the commitment, and an audit trail belongs to the activity feed
(ADR 0044) rather than to a field bolted onto a row a member reads.

**The `Idempotency-Key` is removed and the `409` stays, and both follow from the row's own state.** The state answers "has
this been voided", so a key would add a second record of an act the obligation already records - and voiding an
already-voided obligation answers `200` with the row, which is idempotence by state rather than by key, the same answer
`POST /groups` gives a replay (ADR 0043). `409` is kept for the one thing the state cannot absorb: a settlement run holds
the commitment (`netted`) or has taken it (`settled`), so voiding it would make that run wrong about money that may have
moved. That is a conflict with the world rather than a malformed request - the request would have succeeded an hour earlier
- and it is the only place in the contract where `409` means that.

**This is the first write narrowed by membership, and the order of the checks is the design.** The row is read first,
because the group is only knowable from the row. **Membership is checked second, before the status** - so a caller who is not
a member gets `api.no_such_obligation` whatever the obligation's state is, and cannot use the answer to learn whether a
commitment is open, held by a run, or already voided. Checking the status first would have leaked exactly that, one guess at
a time, and it is the obvious order, which is why the tests assert the pair: the same obligation refuses a stranger with a
`404` and the group's own member with a `409`.

**The status is weighed last:** `open` is voidable, `netted` or `settled` is a conflict, `voided` is a `200` with the row.

**The write is conditional and the answer is read back.** The store updates `WHERE id = ? AND voided_at IS NULL` and
re-reads, so two callers racing on one obligation produce one void and are told the same thing. A check-then-act race would
let the loser restamp the instant - the race the invitation acceptance and the group create both avoid, and there is no
reason for this path to be the one that loses it.

## Consequences

- **`ContractCoverageTest` reports thirty operations, twenty-seven served and three recorded** - labels, execute, webhooks
  and the `POST /fx/quotes` removal candidate.
- **Two codes are served now**, and both are `ExpectedFailures` entries, so the server's catalogue grows by exactly two:
  `api.no_such_obligation` (`404`) and `api.obligation_not_open` (`409`).
- **One code covers two conditions**, as the group reads and the activity feed already do: no such obligation, or not one
  your group's. The difference between the two is 40 bits of guess.
- **A duplicated mapping, deliberately.** `JooqObligationVoids` writes out the row-to-value conversion that
  `JooqObligationStore` already writes, including the quote fetched by id. A shared helper would be a third class with two
  callers, and the two answer different questions. The *decision* - which status a row has - is not duplicated: both call
  `ObligationStatuses`, so a replayer and a voider cannot disagree about whether a commitment is still owed (ADR 0036).
- **The screen followed in the same slice, and the catalog gate found something.** The obligations list grew an actions
  column that offers the void on exactly the rows the server would accept one for (`open`, and never a row still in
  flight), behind a **second press** - the row stays marked `voided` and cannot be undone, so one press asking is the
  difference between a correction and an accident. The mutation carries no `Idempotency-Key` because the contract
  removed it, patches every view of the list (a void changes a row that is already in all of them, unlike a new record),
  and rolls the row back on a refusal. That last part has a test: `api.obligation_not_open` must leave the row `Open`,
  because a screen that kept the optimistic `voided` would be inventing a correction the server refused.
  Writing it also produced a finding from `tools/check-catalogs.mjs`, which is worth recording: the first draft added a
  hint key that nothing rendered, and `I2` failed the build on it. The key was deleted rather than wired to a tooltip -
  the status column already says `Settled` or `In a plan` beside the words "Nothing to do", and a tooltip is
  unreachable without a mouse.
- **The other boundaries still take the caller from the body** - members, invitations, decisions, obligation recording -
  which is what ADR 0042/0043 deferred and this slice begins to undo.

## Alternatives considered - and why rejected

**An `Idempotency-Key` as the contract declares.** It is the pattern the recording path uses, and the header is already in
the document. Rejected because the row carries the state a key would record: voiding twice is one void because
`voided_at IS NULL` guards the update, not because a key table remembers an attempt.

**`409` for an already-voided obligation.** Simpler to reason about - every refusal is a conflict. Rejected because it
punishes a retry of a request that succeeded: a client that lost the first response would be told its own work had failed,
and would have to give up rather than carry on.

**Checking the status before membership.** The natural order - refuse what cannot be done, then ask who is asking. Rejected
because it turns the endpoint into an oracle: a `409` for an obligation the caller cannot see is a fact about somebody
else's group, and the `404` the reads give would be pointless if this write handed the same answer out.

**A `voidedBy` column, so the feed could name who corrected a commitment.** Tempting, and it would make a fourth activity
kind possible. Rejected for now because it is a change to the recording path rather than a slice of this one, and because
an audit column that only the void sets is a column nothing else maintains.

**Deleting the row, with the API deriving "was deleted".** The simplest read. Rejected outright by ADR 0004: a group that
cannot see its own corrections cannot audit them, and P1 says the record is the product.
