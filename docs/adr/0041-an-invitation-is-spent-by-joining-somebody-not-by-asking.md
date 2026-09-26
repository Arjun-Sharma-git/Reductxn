# ADR 0041 - An invitation is spent by joining somebody, not by asking

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`CreateInvite`, `AcceptInvite`, `Invitations`, `JooqInvitations`, `InviteRefusals`,
  `InvitesController`, `V105__invitations.sql`), `apps/web` (`features/invites`, the members screen's invite card, the
  `/invites/$code` route), and the contract: both operations described, the acceptance's missing request and response
  shapes written, `404` distinguished from `410`; 11 API tests, 3 unit tests, 4 browser tests
- **Related:** ADR 0038 (the check that recorded both operations), ADR 0035 (membership as data, and the idempotent
  members write this composes with), ADR 0020 (the body decides who answered, the same identity gap), ADR 0040 (the
  slice before it, and the same "the contract's shape is the reason" move); RULE B12, B15, B17, B20, B21, C1, C3, F1, F8;
  P8

## Context

`POST /groups/{groupId}/invites` and `POST /invites/{code}/accept` were the last unserved operations of MASTER-PLAN
section 6's route tree, recorded by ADR 0038 as one flow whose "acceptance rules are the slice's real work". Four things
about the contract shaped that work before any code was written:


## Decision

**The code is the table's primary key, because the code is what the wire addresses.** `POST /invites/{code}/accept`
carries no group, so a code has to identify the group on its own: codes are unique across the deployment rather than
within a group, and the lookup an acceptance performs is the key. No `id` column was added - it would be a second
identifier for one thing, nothing would address it (the contract's `Invite` has no id, and after creation no operation
names an invitation again), and ADR 0040 had just removed the same shape from a response.

**The window and the limit are the service's, and the absent request body is why.** A code lasts seven days and has no
use limit: an invitation is a link a group shares with somebody it trusts, and what stops it is time rather than a count.
The contract's description states both, so a reader knows what they are getting rather than inferring it from behaviour -
and a group that wants a one-time code needs an operation to ask for one, which is where a caller's voice belongs.

**The code is eight characters from a thirty-two-symbol alphabet.** Forty bits: unguessable enough that codes cannot be
enumerated, short enough to read out loud. `0`, `1`, `I` and `O` are excluded because a code is transcribed by a person,
and the table's CHECK constraint is where that stops being advice - a `text PRIMARY KEY` with no constraint would accept a
code nobody could type, and the exclusion is the whole point of the length. Collisions are settled by the primary key:
the store generates a code, inserts with `ON CONFLICT DO NOTHING`, and retries a taken one, because "is this code free"
is a question only the database can answer.

**An invitation is spent by joining somebody, and the order of the four questions is the decision.** Does the code exist
(then `404`); is the window open (then `410`); is this person already a member of the group (then there is nothing to do,
and **nothing is spent**); and only then spend a use, with the database deciding whether there was one left. The third
question is the one this slice exists for. Membership is idempotent - `(groupId, userId)` is `group_members`' primary key
and its insert does nothing on conflict (ADR 0035) - so "you are already in this group" is a success that changed nothing,
and refusing to spend for a membership that will not be recorded is what keeps a one-time code available for the person
the group actually invited. Spending first would be tidier about exhaustion and wrong about the case that matters.

**Two refusals under one status, because the conditions differ and the remedy does not.** The contract has declared `410`
for "invite expired or exhausted" since the path was added: one status, because a client's next move is the same either
way - ask the group for a current code. The codes stay distinct (`api.invite_expired`, `api.invite_exhausted`) because a
code identifies a condition (RULE B20) and the two conditions are different sentences to a reader: one ran out of time,
the other was already used. A code nobody holds is `404` rather than `410`, and that also matters: a mistyped code is a
code for nothing, while an invitation that ran out existed and is finished.

**The body says who is joining, and the screen says so too.** There is no sign-in, so the acceptance's request carries
`userId` and `displayName` - the same "the member is whoever the body says" the decision endpoint works with (ADR 0020).
The contract's description states that the id is an assertion rather than a verified fact, and the screen acts on that
rather than around it: it **mints** the id and shows it after joining, because a person arriving from a link does not have

## Consequences

- **Serving two operations means deleting two entries.** `ContractCoverageTest` now reports thirty operations, eighteen
  served and twelve recorded with an owner, and it records the title of a list that no longer names a screen: the route
  tree of section 6 is complete, and what is left is identity, activity, groups, execute, void, webhooks and the
  `POST /fx/quotes` removal candidate.
- **The transaction is the acceptance's, and that is RULE B15 earning its keep.** `AddMemberService` needs no boundary -
  one statement settles its own races - but an acceptance is two writes with a promise between them: a use is spent
  *because* a membership is about to be recorded, and a failure between them would spend an invitation for nobody. The
  service is annotated, and the promise is the annotation's.
- **A residual race is stated rather than solved.** The membership question is asked before the use is spent, so two
  acceptances of the same code by the same person at the same instant may spend one use more than they needed. Those
  uses are their own, nothing worse is possible (the idempotent write is what makes a double membership impossible), and
  the alternative - spending first - would take a use from the group every time a member followed an old link.
- **The refusal's reason is read, not inferred.** When the spend fails, the row is fetched again to ask which condition
  stopped it, so nobody is told "used up" about an invitation that merely expired while they were being accepted. One
  extra read on a refusal path buys a refusal that names the truth.
- **A test found the alphabet constraint the honest way.** The first version of the expired-invitation test seeded a code
  with an `I` in it, and the table's CHECK refused it - which is the constraint doing its job on a code no generator
  would produce and a test did.

## Alternatives considered - and why rejected

**Spend the use first, then join.** Simpler to reason about exhaustion, and wrong about the case this slice exists for: a
member of the group accepting a code would spend a use without joining anybody, and a one-time code shared with the wrong
person first would be gone. The order of the questions is the rule.

**Answer "already a member" with a `409`.** A conflict is what the shape of the answer invites - the request is
well-formed and the state of the world is why it did not do anything. Rejected because the members write already answers
that case with a `200` and the row as it stands (ADR 0035), and answering an idempotent call with a conflict is exactly
what P8 exists to avoid. One of the two operations would have been wrong.

**Put a role on the invitation.** Then a code could confer `admin`, and one migration would cover the future. Rejected
because the contract's `Invite` has no role field, and because a one-value column is a vocabulary nobody chose: an
invitation that grants admin is a decision somebody has to make on purpose, with an operation that says so.

**One refusal code for both "expired" and "exhausted".** Fewer codes, one sentence, and the contract lists one status
either way. Rejected because the two conditions are different things to tell a person: "that one has expired" and "that
one has already been used up" lead to the same remedy but are different facts about the code in their hand, and the client
has a sentence for each.

**Require an existing member's id in the acceptance.** It would make "who is joining" answerable from the group's own
rows instead of from the body. Rejected because it inverts who an invitation is for: the person arriving is usually not in
the group yet, and the members list is precisely the thing they cannot read. The contract's body is the honest shape of
the gap until sign-in exists.

**Give the create operation a request body with a window and a limit.** The contract could have carried it - the
operation is ours to write - and a caller who wanted a one-time code would have it today. Rejected because the operation
had no body when this slice started, and inventing one would have been this slice choosing a product policy that nobody
asked for: seven days and no limit is what a shared link means, and it is stated in the contract where a reader will see
it. When a caller needs to choose, the body arrives with the need.

a user id to type and asking them for one would be asking for something that does not exist. Accepting joins as the
ordinary `member` - the invitation carries no role, and a code that conferred `admin` would be a privilege handed out by
a default.

**A code is read in the case it was typed, and normalised once.** The alphabet is uppercase and the store refuses
anything else, so a code typed in lowercase is trimmed and uppercased at the boundary. The alternative - comparing as
given - would answer a spelling mistake with a `404` whose cause is invisible in the request, and the store's strictness
would be the reason rather than the fix.

**The members write's name limit moved into the port.** Both the members write and the acceptance record a name, and the
acceptance refuses a name by the same rule rather than a second one: `MemberRefusals.MAX_NAME_LENGTH` is now the limit, and
the members controller reads it instead of declaring its own. Two controllers with a `120` each is a limit that drifts.

1. **The table did not exist.** Nothing implemented invitations, so this slice created one - and the shape of the data
   followed from the wire rather than from taste, because the paths decide what has to be lookupable.
2. **Creation had no request body.** The operation declared no request schema, so there was nowhere to ask for a shorter
   window or a one-time code. That makes both a deployment decision rather than a default a caller could have changed.
3. **Acceptance had no response schema, and no way to say who was accepting.** `200: Joined` described nothing, and
   there is no `GET /me` or authentication in this build.
4. **Acceptance is addressed by the code alone.** No group in the path - which tells you where the group has to live.
