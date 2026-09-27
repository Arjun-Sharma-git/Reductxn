# ADR 0054 - A group's books are read by its members, and a code is how somebody becomes one

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (`401`/`404` and rewritten paragraphs on nine reads, a new
  `GET /invites/{code}` with an `InvitePreview` schema, the netting preview's missing description),
  `ReadGroupBalances`/`PreviewNetting`/`ReadGroupRuns`/`ReadGroupCycles`/`ReadGroupMembers`/`ReadObligations`/`ReadRun`/
  `ReadTaxReport` (a caller and a `Result`), eight use cases, `SettlementRuns.findFor`, `Invitations.previewOf`,
  `ReadInviteService`, nine controllers, `InvitePreviewResponse`, seventeen API test classes, `DatabaseTest`,
  `entities/invite`, `AcceptInviteScreen`, `GroupsScreen`, `global.css`, the e2e stubs and six specs
- **Aligns with:** ADR 0043 (a group became a row and a caller became required) and ADR 0044 (membership is the whole
  condition) - this is the half of both that was left open
- **Related:** ADR 0041 (an invitation is spent by joining somebody), ADR 0047/0048/0049 (the writes that asked first),
  ADR 0053 (whose refusal code this slice refined), ADR 0021 (one implementation of a read), ADR 0028 (the client's
  first screen, which the paste box navigated to); RULE B12, B15, B20, C1, C3, F1, I2

## Context

ADR 0043 made a group a row and required a caller for `GET /groups`, `GET /groups/{groupId}` and its activity feed.
ADR 0044 made membership the whole condition for those. Everything else the group owns stayed open, and the count was
**nine reads**: balances, the netting preview, the obligations list, the cycles and runs lists, the members list, a run,
its transfers, and the tax report. Each served a bare uuid, and each said so in a paragraph that ADR 0043 had already
made false:

> There is no `404` for a group nobody has heard of, for the reason the members, runs and cycles lists give: groups are
> not tables in this build, so an empty array is true of a group with nothing open and of a group that does not exist.

The clause after the comma expired the moment groups became a table. What made that more than untidy is where the two
halves met: **the client's home screen had a box that took a group id and navigated straight to
`/groups/{id}/netting`** - the plan a group is about to execute, with every amount in it. So the front door of a group
somebody is not in was the group's money, and the group's *name* was protected. A page headed by a uuid whose balances
rendered was the graph of that asymmetry, and its own doc described it as a design: "opening a group by id never needed
to know who was asking".

Three things were true and each is worth separating, because the slice turns on their difference:

1. **The reads were open.** Anyone holding a uuid could read a group's ledger.
2. **The contract already promised otherwise.** `security: bearerAuth` is declared globally, and every operation is
   covered by it unless it says `security: []`. So the document required a session on all nine reads and simply did not
   list the `401` they would answer with - the promise was there, the declaration was not, and the code did not keep it.
3. **The paragraphs recorded the gap as a state of the build** ("groups are not tables"), which was true when they were
   written. ADR 0049's own closing bullet was more honest than the code and named this as the product decision it still
   is: "closing that half is a product decision about how a group is shared, not an oversight."

## Decision

**A group's books are read by its members, and the thing a group hands out to make somebody a member is a code - which
is readable before it is spent.**

- Nine reads resolve a caller and ask `Groups.isMember` in the use case, before anything is read, refusing with the
  existing `api.no_such_group` `404`: one answer for "no such group" and "not yours", so a guessed id cannot be tested
  by comparing two refusals (ADR 0043, 0044, 0045, 0047, 0048, 0049, 0053 - the same rule, finally, on the read side).
- **The check comes first**, and the ordering is load-bearing in two places: a stranger must not have their positions
  read before being refused, and a stranger must not learn which currencies this deployment has tax rules for by
  comparing a `404` against a `422` - ADR 0049's ordering, applied to the reads that net the same rows.
- **The run reads answer `api.no_such_run` instead of `no_such_group`, and that is a refinement rather than a second
  rule.** A run's group is only knowable *from* the run, so `SettlementRuns.findById(runId)` became
  `findFor(runId, callerId)`: the membership is a clause of the statement, exactly as `Groups.forMember`'s is, and a run
  in a group the caller is not in is not a row. Both conditions then answer what one code says - which is what stops the
  endpoint being an oracle for which run ids are real. **This corrected two writes**: `RecordDecisionService` and
  `RequestRunExecutionService` had checked membership themselves and answered `no_such_group`, whose own prose names the
  group id it could not find - so an unauthenticated request holding a run uuid was handed the group the run belonged to.
  ADR 0053 chose that code, and the change is recorded as an amendment there.
- **`GET /invites/{code}` is new, and it is what keeps this from being a subtraction.** A code could be created and spent
  and never read, so a group had nothing to share but an id. The preview answers the group's name, its member count and
  the code's own terms, and refuses with the same three codes the acceptance does (404, 410 expired, 410 exhausted) -
  decided from the same conditions, one step earlier.
- **The preview is the one operation with `security: []`.** The person who asks it is the person a link was sent to, who
  usually has not signed in yet; the code is the capability they were given - eight characters from a thirty-two-symbol
  alphabet, with a window and optionally a limit, which is already the only credential this product hands out (ADR 0041).
  Requiring a session would mean asking somebody to create an account to find out what they are being asked to join.
- **The client's box takes a code and goes to the invitation screen.** The id was never a credential, and the paste box
  was where this build pretended it was.
- **Balances and the preview stopped binding their group id as a `UUID`.** Binding it made Spring judge the path before
  the handler ran, so an unauthenticated caller got a `400` about its request where every other answer here is `401`. The
  divergence was already recorded as "one of the two should move" (`GroupRunsController`); this is the move.

### What is deliberately not decided here

- **Who may invite.** A member may, since ADR 0048, and the role stays unread: `admin` still governs nothing, so a group
  cannot make its codes admin-only. That is the next question about sharing rather than part of this one.
- **Sharing with somebody who must not join.** An accountant, a landlord, a mediator: none of them should become a
  member, and a code is the wrong instrument for them. That is a capability link - a token scoped to a read, revocable

## Alternatives rejected

- **Recording the decision and changing no code** - the position ADR 0049 left open, and defensible on its own terms:
  "the uuid is a capability and whoever has it may read". Rejected because a uuid is not a secret - it is in URLs,
  browser history, logs and dev data - so the policy on offer was *de facto* public read on the most sensitive data in
  the product, and it contradicted the three reads beside it rather than deciding anything about them.
- **Closing the reads and nothing else.** The honest objection: the box existed because somebody handed an id by a person
  who forgot to invite them should be able to open it. Closing without the preview removes that door and gives nothing
  back, which is the kind of change that gets reverted. The two go together or neither does.
- **Keeping the box and having it 404 helpfully** ("this group exists but is not yours") - the existence oracle every
  other refusal here refuses to be.
- **A rule about paths** ("every `/groups/{groupId}/**` read resolves a caller"). It would have missed
  `GET /tax/reports?groupId=`, which is why the slice found that read by walking the *operations*: the group is a query
  parameter there, and a path-shaped rule would have left a group's tax report open with a green build. Recorded as a
  finding, because the tool that suggests itself is the one that would have lied.
- **Requiring a session on the preview**, because "a read of a group's data should need one". It is the person without
  one who asks, and the answer carries a name and a count.
- **Giving the preview more.** Amounts, member names, the invitations a group has made: each is a disclosure the group
  did not make by handing out a code. `memberCount` is the one number, because "which group is this" is unanswerable
  when three groups are called `Flat 3B`.
- **A second code for "not yours" on the run reads** - an oracle for run ids, as above.
- **Hoisting the six private `memberOf` fixtures in the test tree into the one this slice added.** They differ in the
  role and the display name they record, and the shared helper's name is deliberately not `memberOf` so that the
  hoisting stays a change of its own rather than a rename hidden inside this one.
- **Requiring a body whose only field is optional** (the shape ADR 0053 refused for an answer) - not applicable here,
  and named only because the preview's refusal set is the acceptance's refusal set and someone will ask why.

## Consequences

- **The members read can no longer answer an empty list, and the build contains groups nobody can read.** A group with
  no memberships is a state dev data really has; both of its possible answers - an empty list and a `404` - were
  considered, and the refusal is the one that does not claim a member can see a group they are not in. The test that
  asserted "a group with no members answers an empty list" now asserts the refusal, and the *reason* is the finding:
  reading requires being a member, so a group with no members has no reader.
- **Every read test's fixture gained a member and a session.** `DatabaseTest.signedIn` grew an overload that takes the
  person's id, so a test whose rows name `ALICE` signs in *as* Alice and the ledger it asserts and the caller it sends
  are about one person; `memberRow` is the shared membership the nine classes need. Six write-path classes changed too -
  they used the reads as fixtures - and two of them asserted the code this slice refined.
- **The API tests went from 220 to 224, and they are the refusal cases**: a group that is not the caller's, a run that is
  not, a tax report whose group is somebody else's, and four for the preview (a code for a group with no name to show, a
  code for a group that has one, a code that ran out either way, a code nobody holds).
- **The browser suite went from 56 to 58, and six specs changed what they prove.** The read stubs now answer `401`
  without the session's token, so every screen that renders a group's data has proved the client sent a credential; the
  read helpers sign the page in through the flow a person uses. Two specs changed subject rather than being deleted: the
  members read is no longer visible to a signed-out visitor, and a plan is no longer readable without a session - which
  was the sentence that test was written on and this slice corrected.
- **Two real defects surfaced, both invisible until a signed-in page met axe.** The shell's "Sign out" used
  `--rn-accent` as a *text* colour, which `tokens.css` documents as "kept for fills": about 1.9:1 on the surface, and the
  only place in the client that made that mistake. It was invisible because the button renders only when somebody is
  signed in, and no accessibility run had ever been signed in. And the activity stub had never enforced a session even
  though the read has required one since ADR 0044 - the server refused, and the suite could not tell. Both are fixed; the
  second is the shape of the whole slice.
- **ADR 0049's "what is still open" bullet is answered and amended there**, since it is the later statement of it. What
  remains open from it is the `execute` + rail-webhook *mechanism* list, which ADR 0050 and 0051 then served.
- **The next question is named rather than implied**: who may hand a code out (the unread `admin` role), and whether a
  group can share a *read* with somebody who must not join. The first is a policy over an existing column; the second is
  a new kind of principal, and both are in the plan's open list rather than in this slice.

  independently of a membership - and it is a feature rather than a permission, because it introduces a principal that
  is not a user. Named in the plan rather than half-built here.
- **Group schedules.** Nothing still decides when a cycle is due, and this slice does not change that.
