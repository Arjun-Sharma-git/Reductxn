# ADR 0016 - Quorum is over a run's participants, until membership exists

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `workers/settlement` - `Quorum` (package `io.reductxn.settlement.port.in`), `DecideOnRun`,
  `ExecuteRun`; branch `feat/settlement-execution`
- **Related:** RULE S1; ADR 0008; MASTER-PLAN sections 5.5 and 13

## Context

RULE S1 says a run may only execute after quorum: **a majority of members, and every member who owes a net
amount must confirm personally.** It is one of the five rules in `docs/specs/settlement.md`, and it was the
reason `awaiting_approval` was a state nothing could leave.

Writing it revealed that the rule cannot be implemented as written, because **there is no members table**.
`groups`, `group_members` and `invites` belong to `apps/api` (MASTER-PLAN section 5.5), and no migration creates
any of them: the contract has `/groups` and `/groups/{groupId}/members`, and the data behind them does not exist.
So "a majority of members" is a quorum over an empty set, and an empty set is satisfied by nothing - it passes,
which is the worst kind of rule to implement.

Three ways out were considered. Two of them are wrong for reasons worth writing down.

## Decision

**Quorum is counted over the participants in the run's own plan.**

The members of a run are the participants in its stored net positions, minus `fx_clearing`; the debtors are those
of them with a negative position in any currency. Both sets are already written with the run, they are the people
whose balances the run changes, and asking exactly those people is defensible rather than merely convenient.

Three details were decided with it:

- **`fx_clearing` is a participant and not a member.** It is netted like one so a cross-currency cycle comes out
  right (ADR 0012 point 3), and it is not a person. A quorum it could help reach would be the group's own clearing
  account agreeing on its behalf, in the runs with the largest amounts - and it is a participant in *every* run
  whose settlement crosses currencies.
- **A decision is final.** A member who has answered is not asked again, and the primary key on
  `(run_id, member_id)` is what makes that true for two concurrent callers rather than only for one. The
  alternative needs a rule for what a withdrawal of consent does to the approvals granted after it, and the only
  honest version of that rule is "re-derive the run's state on every edit".
- **One rejection ends the run.** The spec says approval is granted once for the whole run, so the run is
  all-or-nothing: a member saying "not this" is not a minority to be outvoted, because nothing has moved and the
  group can open another cycle over the same commitments. `rejected` is therefore a state the run is in rather
  than the absence of a state.

**An approval moves nothing on its own.** The lifecycle draws `awaiting_approval --> executing`, and the last
approval makes quorum *true*; `executing` is entered by execution, because a run that entered it on the last
approval would be claiming the worker had begun submitting transfers when it had not.

## Consequences

- **RULE S1 is implemented and enforced**, which is what makes `awaiting_approval` escapable and execution
  reachable. `RuleCoverageTest` can name the tests that enforce it - which required a change to that file, since a
  rule enforced by a test in another module had no way to be claimed at all.
- **The deviation is visible rather than silent.** This ADR and section 2 of the spec say what the rule's sentence
  left open, and the change when membership arrives is a decision with a record rather than an edit.
- **Quorum can be smaller than the group, and never larger.** A fifty-member group whose cycle nets three people's
  commitments needs two approvals, not twenty-six. That is the substantive difference from a literal reading, and
  it is the reason this is an ADR rather than a comment.
- **The run's report says who was asked.** `Quorum` carries the members, the debtors, the approvers and the
  rejecters, so a refusal can say which debtors have not confirmed rather than only that quorum is short.

## What becomes harder

- **A group cannot express a stricter policy.** "Everyone must approve" and "these two officers approve" are both
  reasonable, and neither is expressible today: the policy is one rule over derived sets. When quorum becomes
  configurable it will need a per-group column, and an answer for what happens to approvals already granted when
  the policy changes mid-run.
- **Quorum's scope is implicit.** It follows from the plan, so a group cannot see who will be asked before a run
  exists. That is a product question about a preview rather than a rule about a run, and it is worth having.
- **A participant who is not a group member at all would still be asked**, because the plan is what counts.
  Nothing can produce that state today - an obligation is between members - and it is the kind of thing the
  membership table will settle rather than a case to guard against now.

## Alternatives considered - and why rejected

**Wait for membership, and leave quorum unimplemented.** This is the state the code was in, and it is honest but
it blocks everything above it: `awaiting_approval` cannot be left, execution cannot be reached, and S1 stays a
sentence in a document. The rule being unimplemented was the largest gap in the module, and it was not waiting on
anything this module could not do itself.

**Create a `group_members` table in `workers/settlement`.** The fastest path to the spec's literal sentence, and it
would give one module's migration another module's table - RULE B17's exact prohibition, and the arrangement that
lets two deployables disagree about who is in a group. Membership is identity, and identity is `apps/api`'s.

**Require every participant to approve.** Stricter than S1 and simpler to explain, and rejected because it makes a
settlement impossible to reach: one creditor who stops answering blocks the group indefinitely, and "majority"
becomes a word in the spec with no meaning in the code.

**Count `fx_clearing` as a member.** Rejected: a quorum satisfied by the group's own account is not an agreement,
and it would be easiest to reach in exactly the runs where the amounts are largest.

## Amendment, 2026-09-26: the intersection had to cover refusals too

This ADR's rule is "quorum is over a run's participants", and it was applied to approvals and **not to refusals**:
`Quorum.countedApprovals()` intersected the answers with the run's own members, while `rejected()` read the raw
rejecter rows. A test written to assert the symmetry (`RecordedDecisionsTest`, this slice) failed on the first run
and showed what the gap meant: with `memberId` a client-supplied claim rather than a fact the server knows
(the same identity gap this ADR's implementation notes describe), **one decision row from anybody could end any
group's run**. An unguarded approval is inert - the run still needs its own members - while an unguarded rejection is
terminal. The asymmetry is why the guard matters more here, not less.

`Quorum.countedRejections()` now applies the same intersection, and `rejected()` is defined over it. The raw rows
stay visible in `rejecters()`, for the reason `countedApprovals()` gives about strangers: a fact that is hidden is a
fact nobody fixes.

The existing unit test that asserted "a rejection is a state rather than the absence of an approval" had used a
rejecter who was not one of the run's members, so it was pinning the hole. It now builds the honest version of the
same case - three members, two approvals and a refusal from the third, who owes nothing - which is exactly the state
where quorum is met *and* the run must not execute.
