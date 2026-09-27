# ADR 0053 - The answering member is the caller, and the record is what that closes

## Status

Accepted.

## Context

Four slices closed the identity gaps in turn: ADR 0043 gave the group writes a caller, ADR 0047 made an invitation's
joiner the caller, ADR 0048 made adding a member a member's act, and ADR 0049 made writing to a group's books one.
Each named what it left behind, and all of them named the same last operation: `POST /runs/{runId}/decisions`, whose
`memberId` arrived in the request body. Four hand-written places said so in the present tense - the contract's
operation description, `NewDecision`'s javadoc, `DecisionRequest`'s, and `RunScreen`'s - and the contract's said the
thing that made it a task rather than a fact: **"Closing this boundary is the same change the group writes made."**

ADR 0047 and ADR 0049 also recorded *why* it was last, and that reason is the part this slice corrects. ADR 0047:
"`POST /runs/{runId}/decisions` is deliberately last, because a stranger's answer is already inert by the quorum's
arithmetic (ADR 0016) and enforcing it would change nothing about the outcome." ADR 0049: it "stays last: a
stranger's answer is inert by the quorum's arithmetic (ADR 0016), so enforcement would change the status a stranger
sees and nothing else."

**Both sentences are true about the run and false about the record.** An answer is inert to the *quorum* - it cannot
end a run, because `Quorum` intersects answers with the run's participants (ADR 0016's amendment) - and it is
anything but inert as a *row*. `JooqActivityFeed.decisions` joins `settlement_decisions` to the group's runs, so
every answer appears in the group's activity feed, attributed to whatever member id the request named. Before this
slice an unauthenticated request holding any run uuid could therefore put a line in a group's history, naming a
member of its choosing, and read it back a moment later. The stranger did not see a different status instead of a
row; the stranger saw `200` *and* the row.

That is the difference the slice turns on, and it is worth stating as a rule rather than as a story: **"inert by the
arithmetic" is a claim about one consumer of a row, and a row has more than one.** The quorum reads answers; the
activity feed reads answers; `GET /runs/{runId}` reads answers. A guard justified by the narrowest reader of a row
is a guard justified by the least of what the row does.

## Decision

**The answering member is the caller, and the caller must be a member of the group whose books the run settles.**

- `memberId` leaves the contract's `DecisionRequest`, and the body loses the `required: true` that existed to make it
  present. Nothing is left in a body but an optional `decision`, so an absent body is an approval - the same default
  the field's absence already meant.
- The guard is `Groups.isMember` on the run's group, asked before anything is written, refused with the same
  `no_such_group` `404` every other group write answers with (ADR 0049). It is the same *fact* rather than a similar
  one: a decision on a run is a write to that group's books.
- **What is deliberately not asked is whether the caller is a participant of the run.** That is the quorum's scope,
  it is a rule over the run's own rows, and re-deriving it here would be this deployable implementing the worker's
  arithmetic from another module's tables - the drift ADR 0020 refuses. A member of the group who owes nothing may
  answer; their row is written and shown and counts for nothing, which is what ADR 0016's amendment decided on
  purpose.
- The caller travels beside the command, not inside it: `RecordDecision.record(UUID callerId, NewDecision)`, the
  arrangement `OpenCycle#open` and `NewCycle` already keep. **`memberId` was removed from `NewDecision` rather than
  kept and filled in at the boundary**, and that is the load-bearing choice: a `UUID memberId` the controller happens
  to set from the session is a field the next caller can set from somewhere else, and the type would go on permitting
  an answer by a member who never gave one. `Decisions.record` takes the member as a parameter for the same reason.
- `Decisions.hasRun` is gone. The run's existence and its group now come from `ReadRun`, which is the reader the
  sibling `execute` endpoint already authorises with, so the two writes on this run refuse in the same order with the
  same codes. Two queries for one fact - "does this run exist" and "which group is it in" - could disagree.
- The operation declares `'401'`, and the controller resolves the caller before it judges the path, which is the
  order `execute` keeps.

## What this does not do

- **Reads stay open to anybody holding a group's id.** The pattern test, the members list, the balances, the runs,
  the cycles, the obligations list and the activity feed all serve a bare uuid. That is a product decision about how
  a group is shared rather than an oversight, and it is still open (ADR 0043, 0049).
- **An obligation's parties stay client-supplied.** `debtorId` names the debt's party rather than its author, and no
  session decides who owes whom. It is the one remaining body-named id in the API and it is not the same kind of id,
  which is the distinction `DecisionRequest` now writes down.
- **No participant check**, as above, and no run-lifecycle check: recording an answer for a run that is still being
  computed is legal and useful, and refusing it would be re-deriving the worker's lifecycle.

## Alternatives rejected

- **Doing nothing, and recording why** - the position ADR 0047 and 0049 took, on the arithmetic argument this ADR's
  context corrects. It would have left the group's activity feed writable by anybody holding a run id.
- **Keeping `memberId` in the body and validating that it equals the caller** - a field that exists only to be
  refused, and a type that still expresses the forge. The field is what made the boundary hard to see; removing it is
  what makes the change structural.
- **Refusing a body that names a member** - the strict version of the same guard. Ignoring it is what ADR 0047 chose
  for the invitation acceptance, and for a better reason than leniency: a test that sends another member's id and
  asserts the caller's row is the assertion that proves the field is inert. Refusing would fail a stale client while
  proving less.
- **Requiring a body anyway**, to keep `requestBody: required: true` - a body whose only field is optional, required.
- **The participant check rather than the group check** - tightest, and wrong twice over: it is the worker's rule,
  and a group member who owes nothing is not a mistake to refuse.
- **Leaving `hasRun` where it was** - it would have had no caller left, and this codebase does not keep a second way
  to ask a question it already asks.

## Consequences

- **One case in `DecisionApiTest` is gone rather than rewritten.** The clearing account's answer was reachable
  through the endpoint because the request could name it; the caller is the only member an answer can name now, so
  the row's own `settlement_decisions_come_from_a_member` constraint has become defence in depth against a bug rather
  than a refusal a client can earn. The path it covered - the store refusing and the API answering a coded `422`
  rather than a `500` - is still exercised, by a word the run does not know.
- **The client's picker went with the field, and so did two catalogue keys.** The member `<select>` on the answer
  form, its `memberId` state and `settlement.decision.member`/`chooseMember` are all deleted; the answer is one click
  for a signed-in member, and a signed-out one gets the API's refusal. The key deletion is not tidiness: **`pnpm
  check`'s catalog gate fails on an unused `en` key**, which is I2 from ADR 0052 - so the property claim that slice
  added is what caught the client half of this one.
- **The browser suite grew the refusal a signed-out member sees**, and the two answer tests now sign in and assert
  the exact body, `{ decision: 'approve' }`. The e2e stub for this operation answers `401` without the session's
  token, exactly as the API does, so a spec that shows an answer recorded has proved the token was sent.
- **API tests went from 8 to 11**; the three new ones are the `401` that writes nothing, the stranger who cannot tell
  a run that exists from one that does not, and the body that names another member and records the caller's answer.
- **ADR 0047's ordering sentence and ADR 0049's open-item sentence are both answered here**, and the amendment is on
  0049 because it is the later statement of the two. The *ordering* judgement they made was sound - last really was
  where this belonged - so what is corrected is the reason attached to it, and the reason was wrong in the direction
  that reads safely: "changes nothing about the outcome" was true of the run's outcome and false of the record's.

