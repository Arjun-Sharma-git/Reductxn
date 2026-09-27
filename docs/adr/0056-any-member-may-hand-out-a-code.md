# ADR 0056 - Any member may hand out a code, and the role word stays unread

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** nothing - this ADR decides rather than builds. It closes the plan's open item "who may hand a code out"
  and corrects the sentence in `apps/api/README.md` that carried it
- **Aligns with:** ADR 0048, which made creating an invitation a member's act and recorded the role word as unread;
  ADR 0055, which put share links under the same rule; ADR 0043, whose additive groups can exist with no `groups` row
- **Related:** ADR 0042 (identity is a phone session), ADR 0035 (the members screen says what a role is worth)

## Context

ADR 0048 closed the write surface and left one question behind it, in its own words:

> **Who may invite.** Any member, today: the check is membership and nothing reads the `role` column, so a group cannot
> make its invitations admin-only. That is a policy decision rather than a gap - it needs somebody to decide whether
> `admin` means anything - and it is recorded rather than guessed at.

It was carried as an open item through ADR 0049, 0053, 0054 and 0055, and during that time three facts accumulated that
make the decision available now:

1. **A group created through the API always has an admin.** `CreateGroupService` records the creator's membership as
   `admin`, with the reason written down: "a later slice that enforces roles would otherwise have to guess which
   membership was the creator's". So the option ADR 0048 said it could not take - an admin-only policy - is not
   *impossible*. It is refused, and the reasons are below.
2. **But the role is a word a member can mint.** `POST /groups/{groupId}/members` takes the role from the request and
   validates it only against the vocabulary (`admin`, `member`); the store's insert is `onConflict … doNothing`, so it
   cannot *promote* an existing member - and it can add a **new** one with any role in the vocabulary, including a second
   identity of the caller's own. Any member can therefore make an admin whenever they want one.
3. **A group can exist with no admin at all.** Since ADR 0043 a group is not required to have a `groups` row, and its
   memberships may have been written by an earlier deployment, a migration or a test fixture through
   `POST /groups/{groupId}/members` with the role it chose. A group in that state has members who can read and write
   everything and no membership whose role is `admin` - and an admin-only policy would leave it unable to make a code,
   with no operation that could appoint the admin it needs.

## Decision

**Creating an invitation or a share link stays a member's act, and the `role` column keeps being recorded and unread.**

The decisive reason is the second fact, and it is worth stating as a sentence because every other reason is smaller:

> **A restriction that anybody it restricts can lift is not a restriction.** A member may add a new member with the role
> `admin` - their own second identity is enough - so an admin-only check would be satisfied by the person it is meant to
> hold back. Adding the check would buy a policy that reads like a control and behaves like a formality.

Three supporting reasons, in descending order of weight:

- **Every member is already trusted with everything a code hands out.** A member may record an obligation, void one,
  approve a run, and read the group's whole ledger. A share link grants *reading* of the positions - strictly less than
  what every member has - and an invitation admits somebody the member chose to admit. Gating either protects nothing
  that a member could not already do directly.
- **The lockout is a real state, not a hypothesis.** Fact 3 is a group this build contains, and the failure it produces
  is the kind this repository keeps finding: not a wrong answer but no answer, with no operation that could fix it.
- **The role has no meaning to enforce yet.** Nothing reads it, nothing changes it except adding a new person, and
  identity here is a phone session (ADR 0042). "Recorded, not enforced" is what the members screen tells a reader, and it
  remains exactly true.

## What would change this

The question is not really about invitations; it is about **who may change a role**, which is an identity decision:

- a role that something *reads* (a route that refuses a non-admin) **and** something that *sets* it after the fact - an
  operation to promote or demote a member, which is the missing half (`POST /groups/{groupId}/members` can only add);
- an identity story that makes "a member" harder to mint than "a second phone number", which is what makes the
  decisive reason above reversible;
- a group setting rather than a role, if what a group actually wants is "only these two people hand out codes" - that is
  a per-group policy about a member, and it is a different feature from a role.

Until at least the first arrives, an admin-only policy would be a check against a word. This ADR is the one to amend when
it does, and the amendment would be a small one: `CreateInviteService` and `CreateShareService` already ask `Groups`
before they mint anything, so the condition has one place to change in each.


## Alternatives rejected

- **Admin-only invitations, as ADR 0048 declined to assume.** It reads like the safer policy and it is the one the
  decisive reason above removes: any member can add a new member as an `admin`, so the condition is one the restricted
  party grants themselves. It would also lock out a group that has members and no admin (fact 3), with no operation to
  appoint one.
- **A per-group setting ("only these members may hand out codes").** This is the honest version of what a group that
  wants a restriction is asking for - a policy about named members rather than about a role word - and it is a feature:
  a store for the setting, an operation to change it, a screen, and a decision about who may change *it* (which is the
  same question one level up). Recorded here as the shape to reach for, not decided.
- **Enforcing the role now, by promoting the creator and refusing non-admins everywhere.** Larger than this question: it
  would need a promotion operation, a demotion operation, a rule about the last admin leaving, and a story for groups
  with no admin. Doing it in service of gating a link would be deciding identity in order to decide invitations.
- **Removing the role column.** Tempting once something reads it not at all, and it is the one thing the creator's
  membership records about who made the group - the fact ADR 0048 and `CreateGroupService` both point at. A column that
  is meant to be read later is not dead weight; it is the alternative to a backfill that would have to guess.
- **Leaving the item open a fifth time.** Four ADRs carried it, and the plan's open list is where items go to be decided
  rather than to accumulate. The facts needed to decide arrived with ADR 0043 (groups without rows) and the members write
  (roles a member can mint), so the item is closed as a decision and this file is where it would be reopened.

## Consequences

- **The plan's open item is closed**, and the api README's sentence about it now points here rather than describing a gap:
  "who may hand a code out" is answered - any member - and the answer is a decision with reasons rather than an absence
  of one.
- **Nothing in the code changes, and that is what a decision ADR is for.** `CreateInviteService` and
  `CreateShareService` ask `Groups.isMember` and nothing else; the members screen's caption ("Recorded, not enforced")
  stays true; `MemberRefusals.ROLES` keeps accepting a word no route weighs.
- **One thing is now recorded that was only implied before: a member may mint an admin.** It is a property of the members
  write (any member may add anybody, with any role in the vocabulary), it has been true since ADR 0048, and it is the fact
  that decides this question - so a reader who assumed the role means something has a sentence to find.
- **The next slice that touches roles has a starting point**: the three conditions under "What would change this", in
  order, each of which is a slice rather than a line of code.
