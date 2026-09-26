# ADR 0025 - A person's word is evidence, so a resolution is attributed

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` (`V509`, `TransferResolution`, `ResolveTransfer`, `ResolveTransferService`,
  `RunOutcome`, `TransferStatus.isOffered`, `Runs.resolveTransfer`, `SettlementCommand resolve`)
- **Related:** ADR 0017, 0019, 0021, 0022, 0024; RULE S2, S3, S6, B15, B17; `docs/specs/settlement.md` sections 4.4,
  4a
- **Decides:** who may assert that money moved - the question ADR 0024 deliberately left open

## Context

ADR 0024 ended with a report and a named gap. A rail that stops answering cannot be resolved by the worker, because
the absence of an answer is not evidence of anything except silence: writing `failed` would assert that the money
did not move and writing `confirmed` would assert that it did. So the worker tells a human which movements nobody
has decided - transfer, run, the state the rail left it in, the rail's own last words, how long - and stops there.
It also left the next step written down: an operator who has *learned* the answer from the rail needs a way to
record it, and that needs the booking path a confirmation uses and a decision this project had not made - who may
assert that money moved.

That decision is the hard half, because everything else in this module is arranged around not making it. Quorum
(RULE S1) exists so that one admin cannot move a group's money; approvals arrive through a port because approving is
a member's act and a command that did it for them would settle a group's obligations with a keystroke no member
made; the ledger is append-only because a movement cannot be taken back. A resolution is the one place where a
single person's word writes a state that money depends on - it can complete a run, book a movement, or end a
transfer's retries for ever - so it is exactly the place where "who said so" has to be part of the write rather than
a log line beside it.

## Decision

**1. A person may, and the record names them and what they looked at.** `--reductxn.command=resolve` takes the run,
the transfer, the outcome, `--reductxn.by` and `--reductxn.basis`, and `V509` stores `resolved_by`,
`resolved_basis` and `resolved_at` beside the state. All five options are required and the value type refuses an
attribution in pieces, so a resolution with no name on it cannot be built, let alone written.

That is the whole of the answer to "who may assert that money moved": a person may, in writing, and nothing in this
build checks the writing. There are no identities here - membership, sessions and roles are `apps/api`'s and do not
exist - so `resolved_by` is a string the operator supplies: an **attribution, not an authentication**. Storing it
anyway is a deliberate trade of false confidence for a real fact: an auditor's question is "who said the money
moved", and a command that refused to record a name would leave that question unanswerable while pretending the
problem had been solved. When identities exist, this column becomes a foreign key and the command becomes an act
somebody was authorised to take - a decision, not an edit. This is RULE S6.

**2. A resolution is a claim, not a call.** The use case holds no rail: the operator already has the answer (that is
what the report exists for), and asking the rail again would make the command's outcome depend on the very silence
it exists for. Two consequences follow, and both are decisions rather than accidents: no other transfer of the run is
offered to the rail - an operator who resolves one movement has not asked for the run to be attempted, and a command
that quietly did that would move money nobody asked it to move - and the run's gates are not re-read, because a
resolution is an answer about one movement rather than a new attempt at the run.

**3. The booking is the confirmation path or it is nothing.** `confirmed` writes the state and appends
`Bookings.settlement` for the transfer's own coordinates against the locked chain head, in one transaction, exactly
as `ExecuteRunService` books a rail's confirmation (ADR 0017) - with the entry id derived from the transfer, so even
a resolution that somehow ran twice books one movement (RULE S2). `failed` writes the state and books nothing: a
posting there would be the ledger recording a movement nobody made, which is the same reason the worker refuses to
guess from silence in the first place.

**4. The run's fate follows from its transfers, by the rule execution already used.** A confirmed answer can
complete a run and a "did not move" answer ends one as `failed`, and rather than deciding that in a second place,
`RunOutcome` was extracted from `ExecuteRunService`'s private method and both writers now call it. This is ADR 0021's
argument one level up: two writers of one fact are two answers waiting to disagree, and the failure mode is specific
- a state added later and taught to only one of them. It lives in `application` (both callers are use cases, and no
adapter needs it), which is `Backoff`'s placement rather than `Escalation`'s.

**5. An answer that has arrived is never rewritten, and three things cannot be resolved at all.** A resolved
transfer refuses a second resolution, and the refusal carries what it is protecting - who decided, when, on what
basis - because the case that meets it is an operator re-running after a slow response, who needs to see that the
stored answer is their own. A `pending` transfer refuses too: no rail was ever asked, so there is no answer to have
found out, and if money moved anyway then the missing fact is a submission rather than a decision. A transfer that is
not in the run the caller named refuses, which is why the command takes both ids: a typo resolves nothing rather
than resolving another run's transfer.

**6. Patience is a prompt, not a permission.** Nothing here requires the transfer to be past
`reductxn.settlement.escalation.after`. That policy is how the worker decides when to *tell* somebody; once somebody
knows, how long it took to find out is not a precondition. It also covers the case the report does not cover at all:
a `submitted` transfer the rail accepted and went quiet on *without* a call going unanswered is in no escalation
report, is polled for ever, and can still be resolved by a person who asked about it.

## Consequences

- **The loop from section 4.4 closes.** The report tells somebody which movement to ask about; the resolution is the
  way back; and because the write clears the silence, the transfer stops appearing in the report it came from. The
  test that asserts exactly that (`aResolvedTransferLeavesTheEscalationReport`) is the smallest statement of the
  whole arrangement.
- **A resolved transfer is attributed, and the attribution travels with the detail.** `rail_detail` keeps the rail's
  last words and is deliberately *not* overwritten, so a row can read "the rail last said: the network is down"
  beside "resolved by priya at 14:03 on the basis of rail statement 4417". A detail without its source is a rumour;
  the columns make the source part of the row rather than part of a log.
- **The duration of the silence is not kept after the answer.** `V508`'s `unanswered_since` and the retry count are
  cleared, as they are for a rail's answer, because a resolved transfer is not waiting and not silent - and that is
  what keeps the escalation report a query over unanswered transfers rather than over history. The cost is honest and
  small: how long an outage lasted is in the escalation log lines, not in the row. Keeping it would have meant the
  report filtering on status as well as silence, i.e. two conditions for one idea.
- **The resolution is not reversible.** There is no "un-resolve", because the state it writes is a fact about money
  and the columns are the record of who said so. An operator who is wrong has created a state that has to be
  corrected by another movement - a booking and a posting, which is an accounting act - rather than by editing the
  row. That is a deliberate consequence of the append-only ledger (L3), not an oversight.
- **The stored gate verdict is not rewritten.** `V507`'s conclusion carries the status it was reached for, and a
  resolution changes the run's status afterwards. The verdict is about an instant - "the gates were satisfied for a
  run in `executing` at 11:00" - and the run's *current* status is the run's own row. A conclusion outlives its run;
  ADR 0023 said so, and this is the first time it happened.
- **A client-facing projection of the attribution is `apps/api`'s to make.** The columns are on a table the API
  already reads (RULE B17), so a member's view of "who said this money moved" is a field away - not added here,
  because the worker has no surface and this slice did not need one to be true.
- **The remaining gap is identity, and it is now named.** Nothing verifies `resolved_by`; the command is only as
  accountable as the shell it was typed at and whatever that deployment does about access. `members`, sessions and
  roles are Phase 3's; when they exist, this is a foreign key and a permission check.

## Alternatives considered - and why rejected

**A resolution with no name, logged instead.** Cheaper and wrong: a log line is not a record of an act that moves
money - it is per-deployment, rotatable, and not readable from the row a client or an auditor will look at. The
question this write raises is "who said so", and the answer has to be in the same place as the claim.

**Make it a member's decision, like an approval.** Superficially attractive - the machinery exists (`RunDecision`) -
and it confuses two facts. An approval is a group *permitting* a settlement; a resolution is somebody *knowing* what
a rail did. Members would be asked to report on the rail's behaviour, and an operator cannot approve a settlement.
The actors and the facts are both different.

**Require two operators, or a second signature.** The right shape eventually and the wrong time now: without
identities a second name proves nothing about the first, and it would double the ceremony around a fact whose source
is the rail rather than the group. It arrives with roles - and the columns are already the shape it needs.

**Have the worker ask the rail once more as part of the command.** Then the write would depend on a rail that is
still silent - which is what the operator has already worked around - and on a network call inside a command whose
whole value is that it makes none.

**An API endpoint for it.** The worker has no HTTP surface and must not grow one; and this is not a member's act, so
a member-facing route would invite exactly the assertion this ADR is careful about.

**Let the operator say when the money moved (`--reductxn.moved-at`).** The movement happened at an unknown instant
during the silence - nobody knows it, and the rail's statement often does not say. The ledger's instants are facts,
and the only fact here is when the answer was *recorded*; letting an operator date a posting backwards would put an
unevidenced instant into a table whose other instants are all observations.

**Make a second resolution a no-op rather than a refusal.** A no-op hides which answer is stored: an operator
re-running after a slow response could not tell their own write from a colleague's, and a contradicting answer would
be silently discarded. The refusal names who decided, when and on what basis - which is the information the caller
needs and the record the transfer needs.

**A new transfer state, or a "resolved manually" flag.** The state is about money, not about who asked - and
`confirmed`/`failed` already mean "an answer arrived" whichever side gave it. The attribution columns say the rest;
a state would put a monitoring fact into the vocabulary the run's fate and the ledger are computed from. ADR 0024
rejected the same move for `stalled`.

**Continue the run after resolving.** Calling `ExecuteRun` (or the driver) once the transfer is answered would offer
the run's other transfers to the rail - money moving because an operator recorded something else. A resolution is
one answer about one movement; anything else is the driver's job, and the next pass will pick the run up on its own.
