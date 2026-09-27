# ADR 0043 - A group becomes a row, and a caller becomes required

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`V107__groups.sql`, `NewGroup`, `GroupView`, `CreatedGroup`, `CreateGroup`, `ReadGroups`,
  `ReadGroup`, `GroupRefusals`, `Groups`, `JooqGroups`, the three services, `CallerResolver`, `GroupsController`,
  `GroupResponse`, `NewGroupBody`), `apps/web` (`entities/group`, `features/groups`, the home route, the group layout's
  heading), and the contract: three operations described, `409` removed and `200` added, `401`/`400`/`422` declared,
  `Group` documented; 13 API tests, 3 browser tests
- **Related:** ADR 0035 (members, and the promise this table's read already needed), ADR 0041 (the invitation flow, which
  assumes a group has a base currency), ADR 0042 (identity, and the enforcement this slice starts); ADR 0013, 0014 (what
  the reporting currency and the settlement zone mean); ADR 0037 (one condition, one code); RULE B15, B17, B21, C1, F1, F4,
  F8; P8

## Context

`GET /groups`, `POST /groups` and `GET /groups/{groupId}` had been in the contract since the first slice with nothing
behind them, and `V104` said why: "a group in this build is an identifier that rows agree on; nothing about a group is a
property of the group, so a `groups` row would hold an id, a timestamp and a name nobody has. It arrives with the thing
that needs it."

Three things changed, and only one of them is this slice's doing:

1. **Identity exists.** `GET /groups` asks "which groups am I in", which no earlier boundary could answer at all.
2. **The client's home screen was a placeholder that said so.** It asked for a group id to paste, and documented itself as
   "the placeholder that RULE F4's thin route shape makes cheap to replace with a real group list".
3. **Four gaps in the recorded list were blocked on this one.** The invite flow already assumed a group had a base
   currency (ADR 0041); the cycle boundary wants a settlement zone; the members read wanted a name for the group above it;
   and activity, schedules and policy all read the row this file adds.


## Decision

**The `groups` table is additive, and nothing existing has to satisfy it.** Every `group_id` in this database - in
obligations, members, cycles, invitations - is a bare uuid with no constraint tying it to a group, and development data has
ids whose group nobody ever created. So this table does not become mandatory: rows keep accepting any uuid, a group with
commitments and no row keeps working, and what a row adds is a name, a currency and a creator. A foreign key from
`obligations.group_id` to here would have been the tidier schema and would have made every existing row unrecordable. This
is why `GET /groups/{groupId}` answers `404` for a group this build is full of, why the client's group layout falls back to
showing the identifier, and why the home screen kept its paste-a-group-id box: all three are the same fact, seen from three
places.

**Three operations, and all three require a caller - which is the first enforcement in this build.** `GET /groups` cannot
be answered without one; `POST /groups` records who made the group and makes them a member; `GET /groups/{groupId}` decides
membership. It would have been consistent with every earlier write to take the caller from the body - a `creatorId` field,
the way a decision carries its `memberId` - and that is exactly why this slice does not: these operations are new, so
nothing breaks, and an operation written after identity exists has no excuse for accepting a uuid for the caller
(ADR 0042's own ending). `CallerResolver` is the one place a token becomes a person, shared with `GET /me`.

**A request with no credential is refused before its body is looked at.** "Who is asking" is not something a body can
answer, and a malformed body should not be able to change which refusal a caller receives.

**Creating a group makes its creator an `admin` member, in one transaction.** A group whose creator was not a member is
invisible to the only person who could have asked for it - `GET /groups` answers with the groups somebody belongs to - so
the two writes are one fact and one transaction (RULE B15). The role is recorded and not enforced, like every role here,
and the name the membership records is the deployment's name for the person: their number, until there is a profile screen.


## Consequences

- **`ContractCoverageTest` reports thirty operations, twenty-five served and five recorded** - labels, activity, execute,
  void, webhooks and the `POST /fx/quotes` removal candidate. The three group operations were the last of section 6's route
  tree, so what remains is not screens.
- **The home screen is a real group list, and the placeholder's paste box survives it.** Both exist because both are true:
  a list of what you belong to, and an id somebody was handed for a group that only exists as commitments.
- **The client's group layout shows a name now, and falls back silently.** It is the one caller `GET /groups/{groupId}` has,
  and the fallback is what keeps it from breaking screens whose groups have rows of commitments and no group row.
- **Two bugs were caught by these tests, both worth more than the tests cost.** The first version of the jOOQ read joined
  the caller's membership, which made the create path's own read-back unable to find the row it had just inserted - the
  membership is written *after* the group - so the count is now a correlated subquery. And the browser stub answered the
  group list to anybody, which made the signed-out case unrepresentable; it enforces the session now, which turned two
  passing tests into honest ones.
- **What is still open, named rather than implied:** nothing enforces `maxMembers`; roles are still unread; there is no
  operation that renames a group, changes its currency, sets its timezone or removes a member; membership rows written
  before identity name uuids no `users` row holds, and reconciling them is its own decision; and enforcement still stops at
  `/me` and the three group operations - the members write, the invitation acceptance and the decision endpoint keep taking
  what the body says.

## Alternatives considered - and why rejected

**A foreign key from every `group_id` to this table.** It is what a reviewer would ask for, and it would make "a group with
commitments and no row" unrepresentable. Rejected because that state is this deployment's development data and its existing
tests: the migration would fail, or the rows would have to be invented a group - and inventing a name for somebody's group
is worse than knowing you have not been told it. The additive table plus a `404` the client handles is the honest version.

**Take the creator from the body, as every earlier write does.** Consistent, and one fewer concept. Rejected because
consistency with a workaround is not a reason to keep it: those writes take the caller from the body because identity did
not exist when they were written (ADR 0020, 0042). New surface should not reproduce it.

**Make a group's name unique per creator, and keep the `409`.** Then the declared status would have had a condition.
Rejected because it is a product rule invented to satisfy a schema: two households can be called the same thing, and the
refusal would land on the second person to type "Home".

**Enforce the settlement zone, or default it to UTC.** The first would make every group choose a timezone before it can be
created, which is a decision most people have never had to make about a group chat; the second would decide which cycle a
commitment falls in, silently, for everybody who did not. The field is optional in the contract and nullable in the table,
and the ADR records that a group without one cannot be scheduled yet.

**Serve `nettingPolicy` as a sentence composed from the group's settings.** The schema allows it and a client would show it.
Rejected because the settings do not exist: the sentence would describe a policy configuration this build has not made, and
a member reading "netting: pairwise" would believe somebody had chosen it.

**Enforce `maxMembers` in the members write.** It would make the column true. Rejected because that write serves every group
id in this database, including the ones with no row - so the cap would apply to some groups and not others, and the rule
would depend on whether somebody had created the group through `POST /groups`. It is recorded, like `role`, until there is a
group everywhere.

**Idempotency is scoped to `(creator, key)`, and a replay answers `200` with the record.** The contract requires the header
and there is no group in the path to scope it by. `ON CONFLICT DO NOTHING` plus a read-back is the shape the obligations,
members and invitations writes use: the unique index settles the race, and asking "has this key been used" first would lose
it and report the loss as a failure.

**`409 Group already exists` is deleted, and `200` is declared in its place.** That line was contract surface with no
condition behind it - and a group's name is not unique, so there was no honest condition to give it. Nothing calls it, so
removing it costs no caller anything; inventing a name-uniqueness rule to keep it would have been a product decision made to
satisfy a schema.

**Four fields are stored and two are not served at all.** `max_members` and `role` are recorded and nothing consults them;
`settlement_timezone` is nullable, because a default would decide which cycle an obligation falls in for a household in
Kolkata (ADR 0014, point 6), and the schema already makes it optional; `reporting_currency` defaults to the settlement
currency at creation and is presentational (ADR 0013). `nettingPolicy` is **not served**, though the schema allows it: the
sentence it would carry summarises a policy configuration this build does not have, and a string composed from nothing
would read as a decision somebody made.

Two things in the contract also had to be decided rather than implemented:

- **`POST /groups` declared `409 Group already exists`** and nothing behind it ever had a condition. A group's name is not
  unique - two households really can be called "Flat 3B" - so the only "already" this build can decide is a replayed
  idempotency key.
- **The global `bearerAuth` declaration had one reader.** ADR 0042 ended by recording that enforcing it everywhere would
  break every existing caller at once, and that doing so was the next slice rather than a line of code smuggled in.
