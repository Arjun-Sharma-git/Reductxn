# ADR 0035 - A promise in a contract is not an implementation

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `V104__group_members.sql`, `GET`/`POST /v1/groups/{groupId}/members` in `apps/api`, the members screen
  in `apps/web` (`features/members`, the route and the nav link), `Field` moved to `shared/ui`, and the contract's
  `Member`/`MemberRequest`
- **Related:** ADR 0021 (one description of a thing), ADR 0033 (a promise removed rather than faked), ADR 0034 (the
  same habit applied to the members screen's predecessor); RULE B12, B17, B20, F4, F8; P8

## Context

`GET /groups/{groupId}/members` has been in the contract since the obligations slice. The client has read it ever
since, from **three screens**: the obligations list and its filters, the recording form that cannot pick a debtor or a
creditor without it, and a run's answers. The endpoint did not exist. In development that meant every screen that
names a person fell back to showing a uuid - the fallback the member catalogue documents as its honest option - and
the recording form had nothing to offer in its two selects.

**No gate could see it.** The client's browser tests stub the API, so they passed against a fixture that was
generated from the contract. `apps/api` had never asked for members, so it had no reason to implement them. And the
contract's own check - that the generated client types match the document - was satisfied, because the document was
the problem. A promise in a contract with nothing behind it is a bug that every suite agrees is fine, and this one had
been through seven slices of review.

Two smaller lies were in the same schema, and the slice that touched it had to decide their fate:

- **`POST /groups/{groupId}/members` said "Add a member directly (admin only)".** There is no identity in this build.
  There is nobody whose `admin` row could authorise anything, so the claim was a check that could not be written.
- **`Member.creditLimit` was a `Money`.** Nothing sets a credit limit, nothing consults one, and nothing would ever
  have filled the field.

And the question underneath both: where would members come from? `settlement_cycles` and the obligations are filled
by flows a member walks; membership had no flow, no table, and - per the plan's ownership table - was supposed to be
`apps/api`'s own data (`groups`, `group_members`, `invites`), none of which existed.


## Decision

**1. Both operations exist, and the read has no limit.** `GET` answers the group's members ordered by
`joined_at, user_id` through a new index (`group_members_by_group`), and `POST` records one. **The read takes no
`limit`, unlike the runs and cycles lists**, and that is a decision about shape rather than an oversight: runs and
cycles accumulate without bound, so a page of the newest is the useful question, whereas membership is bounded by how
many people are in the group - and every screen that names anybody needs the whole set. A page of members would be a
catalogue that silently lacks the name a member is looking for.

**2. Membership is data, not something derived from the group's activity.** The alternative was real and cheap:
`GET /members` could return the distinct parties of the group's obligations, which needs no table, no write and no
identity, and would have been implemented in an afternoon. It is wrong for two reasons that both show on a screen - a
member who has promised nothing yet is missing from the list of people who can be named, and the name would be a
uuid, because obligations carry ids and this build has no user records to look a name up in. A member's name is a
fact the group agreed on, so it is stored.

**3. The write exists because a read with no writer is a screen that is empty forever.** This is the decision that
cost the most and is the easiest to argue against: an unauthorised `POST` on a build whose largest recorded gap is
identity. It is here because the alternative - shipping the read alone - produces a members screen that is always
empty in every deployment, which is the same class of failure as the missing endpoint this ADR is about, one screen
further on. The endpoint is unauthorised *and says so*: the "admin only" claim is removed from the summary, and the
description states plainly that identity is the recorded gap, which is the stance the decision endpoint already takes
(ADR 0020).

**4. Idempotent by rule, and an add rather than an upsert.** `(groupId, userId)` is the primary key, so a second
request for the same person answers `200` with the row as it stands instead of a `409` - a caller asking twice has
made no mistake (P8) - and the insert is `ON CONFLICT DO NOTHING` rather than a merge. That second half is the part
worth stating: an upsert here would let any caller rename anybody by asking again, and renaming belongs to the admin
operation a build without identity cannot recognise.

**5. There is no `groups` table yet, and this slice deliberately does not create one.** A group in this build is an
identifier that rows agree on; nothing about a group is a property of the group, so a `groups` row would hold an id, a
timestamp and a name nobody has. It arrives with the thing that needs it - the schedule and policy that decide when a
cycle is due - and until then a foreign key to it would force every membership write to invent a group name.

**6. `Member.creditLimit` is gone from the contract.** A field nothing sets, nothing consults and nothing would ever
fill is a promise a client waits for; removing it is the habit ADR 0033 applied to `close`, and it comes back with
the policy that enforces it.

**7. `Field` moved from `features/obligations/ui` to `shared/ui`.** The members form is the second screen to need a
label, a hint and an error in the order a screen reader reads them, and this module's own rule says a feature's

## Consequences

- **Three screens stopped showing uuids.** The obligations list, its filters and the recording form read the same
  catalogue this slice implements, so the fix reaches all of them without touching any of them: the members list is
  the read they were already calling. That is the argument for fixing a read rather than a screen - the members screen
  is where the gap becomes *visible*, not where it was.
- **Twenty-four new tests, and two of them are about the absence.** Fourteen in the API: six for the read (an empty
  list rather than an error, the names and roles it serves, the order they joined asserted where the ids sort the
  other way, the same-instant tie-break, one group's members not being another's, a coded `400`) and eight for the
  write (the row the read serves back, a second request answering `200` with the existing row, a *name not changed* by
  a second request, one person being a member of two groups, an admin recorded as one, a role this build does not
  store, four malformed requests writing nothing, and a name past the contract's length refused rather than cut). Ten
  on the client: six unit tests for the form's model and four browser tests for the screen. The Java suite goes from
  438 to 452.
- **The test fixture list was a list, and forgetting it was silent.** `DatabaseTest.resetSchema()` deletes the tables
  it knows about, and its own documentation claims truncating is "simpler and impossible to forget" - which the new
  table disproved on the first run: `group_members` was missing from the list, the rows leaked into the next test
  class, and the failure appeared as *that* class's fixture assertion ("an empty list" that was not empty) rather than
  as anything pointing at the reset. Fixed in the same commit, with the comment above it corrected, and recorded as
  the kind of statement a build should not make about a list it does not enforce.
- **What is still open, and where a client meets it.** There are no invites (`POST /groups/{groupId}/invites` and
  `/invites/{code}/accept` are still contract promises with nothing behind them, and they are the honest way to add a
  member: an invite is how somebody consents); nothing can rename or remove a member, because that is the admin
  operation identity would authorise; a credit limit has no home until a policy consults one; and the *role* is a word
  no code reads - which the screen says out loud rather than letting a reader assume it is enforced.
- **This slice fixed one instance of a class, and auditing the class was worse than the instance.** After the change,
  the contract's thirty operations were compared against the six controllers: three more reads the client calls have
  no handler - `GET /v1/obligations` (the obligations *list*, the app's main screen), `GET /currencies` (the exponent
  every money component needs) and `GET /fx/rates` (the rate the recording form locks) - along with the ones the plan
  already recorded (balances, tax, groups, activity, invites, auth, execute, void, webhooks). The audit's own first
  version mis-read `@GetMapping(produces = ...)` as an operation at a made-up path, so its report was wrong until it
  was corrected and spot-checked against two endpoints whose answer was known. Both halves are now recorded in the
  plan: the gaps, and the check that should report them with their owner rather than waiting for somebody to notice a
  uuid.

## Alternatives considered - and why rejected

**Deriving members from the obligations' parties.** Free, immediate, no table and no write - and it answers a
different question. A member who has joined but promised nothing would be missing from the list of people who can be
named, which is exactly the list the recording form needs, and every name would be a uuid because there is no user
record to look one up in. The cheap version of this read is the one that does not fix what was broken.

**Shipping the read and leaving membership provisioned by an operator.** The honest minimum on paper, and in practice
a members screen that is empty in every deployment: a read with no writer is a screen nobody can use. Recording a
member is a small write with a rule for a key, and the alternative to shipping it is shipping a dead screen.

**A `409` for a member the group already has.** Defensible as "you asked for something that already exists", and
wrong under P8: the operation is idempotent by construction, so the second answer is the idempotent one (`200` with
the row), and a conflict would report a caller's harmless retry as a mistake.

**An upsert, so a caller could also correct a name.** It would make the endpoint do two jobs, and the second one
would be unauthorised renaming: any caller could change any member's name by asking twice. Correcting a name is an
admin operation, and this build cannot tell an admin from anybody else.

**A `limit` on the read, for symmetry with the runs and cycles lists.** Symmetry is not a reason: those lists grow
with a group's history and this one grows with its membership, and a paged catalogue of members would silently omit
the person a member is looking for.

**Creating the `groups` table now.** It would let `group_members.group_id` be a foreign key instead of a bare uuid.
It would also require every membership write to have a group name to invent - which is a fact nothing in this build
has, and the reason `obligations` writes its `group_id` the same unconstrained way. The table arrives with the group
policy that needs it, and the plan records that.

internals are private (RULE F8) - so the choice was to copy it or to share it, and the barrel's own comment ("anything
that is simply a nice button waits until a second screen wants it") is what decided. Its refusal prop became
structural (`{ code }`) rather than an obligations type, so no feature leaks through `shared`.
