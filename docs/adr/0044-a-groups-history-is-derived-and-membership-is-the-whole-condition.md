# ADR 0044 - A group's history is derived, and membership is the whole condition

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`V108__invitations_activity_index.sql`, `ActivityEvent`, `ReadActivity`, `ActivityFeed`,
  `JooqActivityFeed`, `ReadActivityService`, the activity endpoint on `GroupsController`, `ActivityEventResponse`, and
  `Groups.isMember`), `apps/web` (`entities/activity`, `features/activity`, the `/groups/$groupId/activity` route and its nav
  link), and the contract: the operation described, `ActivityEvent` defined, `cursor` removed, `400`/`401`/`404` declared;
  5 API tests, 2 browser tests
- **Related:** ADR 0043 (the additive group row this read had to stop requiring), ADR 0035, 0041 (members and invitations,
  the two rows it reads), ADR 0016 (the settlement worker's decisions), ADR 0032/0036 (a derived status, and the same
  reasoning applied to a feed); RULE B15, B17, B20; P8

## Context

`GET /groups/{groupId}/activity` was **the least specified thing in the contract**: a summary, a `cursor`, a `limit`, and a
`200` whose entire description was "Activity page" - no schema, no failure, no description of what an event is. The
coverage list therefore recorded it as a gap whose rows already existed, which was true: memberships, invitations and
settlement decisions were all being written by other slices.

Two of those rows also turned out to be less informative than the gap's wording suggested. `invitations` has no creator
column - the create operation is unauthenticated in this build, so there was nobody to record (ADR 0041) - and acceptance
increments a use count and writes a membership rather than recording who accepted. `settlement_decisions` has no
`group_id` either: a decision is about a run.

## Decision

**There is no activity table, and there is not going to be one.** The feed is derived from the three rows that already
record an act: a membership (`joined_at`), a settlement decision (`decided_at`), and an invitation (`created_at`). An
`activity_events` table would be a second copy of facts those rows state, and the first write that updated one and not the
other would make the two disagree - with the log being the copy nobody can check. The same reasoning as ADR 0036's derived
obligation status, and the same conclusion.

**Three kinds, and what is *not* in the feed is a decision.** Obligations, cycles and runs each have their own read and
their own screen. A feed that repeated them would be a second view of one set of rows that could disagree with the first
about ordering, filters and status - and what a member actually wants here is the group's *social* history: who joined, who
answered, what was asked.

**The shape is defined here, because nothing defined it.** `ActivityEvent` is `kind`, `at`, and the fields that kind has -
`memberId`/`displayName`/`role` for a join, `memberId`/`decision` for an answer, `uses`/`maxUses` for a code. **There is no
`detail` string**: a server that composed "Amit joined on Tuesday" would be rendering one language at a client that may be
reading another, and would be choosing the sentence a client shows (RULE B20, ADR 0014). The mapping from kind to fields is
in the schema rather than left to be discovered.

**`cursor` is deleted; `limit` stays and is clamped.** The document promised a cursor and no limit - a promise to answer
with every event a group has ever produced. The obligations read already made this decision for the same reason: a page is
what a screen can render, and a cursor needs a total order that stays stable as a union of three sources grows, which this
build has not earned. The order is newest first, tie-broken by kind and then by member, so a page is total. A limit below
one is clamped up to one rather than refused, because the schema declares a maximum and no minimum - and a page of zero
events is not a page. A limit that is not a number is `api.request_incomplete`, the module's vocabulary for a malformed
parameter.

**Membership is the whole condition, and it is asked of the memberships alone.** The obvious implementation - reuse the
group read's narrowing - made this the **one group read that required a `groups` row**, which broke it for every group in
this build that has commitments, members and invitations and no row (ADR 0043). The store gained `isMember`, which asks
`group_members` and nothing else, so the feed serves exactly the groups the members list serves. The refusal is the group
read's single code, `api.no_such_group`, for "no such group" and "not a group you are in" alike - a feed most of all, because
it is the read that names people.


## Consequences

- **`ContractCoverageTest` reports thirty operations, twenty-six served and four recorded** - labels, execute, void,
  webhooks and the `POST /fx/quotes` removal candidate.
- **The gap's own wording was wrong in one place, and it is recorded rather than quietly fixed.** The entry said "the member
  and invite screens link to it"; no screen linked to it, because the operation did not exist. A nav link now does, and the
  entry is gone.
- **`V105`'s deferred index arrives.** That file said the index it did not create was for "the activity read (still recorded
  as a gap)", and that it would be created when the read existed. It does, so `V108` creates it - and it is ordered
  descending, because the feed is and the read is always clamped to a page.
- **The read crosses the module boundary, and that is now three reads that do.** `settlement_decisions` belongs to the
  settlement worker (RULE B17); this module reads it and writes nothing, exactly as it does to derive an obligation's status
  and to list cycles. The join is through `settlement_runs.group_id`, which is the run's own column - one join, not the two a
  reader might expect from "a run belongs to a cycle".
- **A bug the tests caught, and the first version shipped with it.** The feed reused the group read's membership narrowing,
  which requires a `groups` row. The test that asserts a bare-uuid group has a history failed with a `404`, which is how a
  member would have met it: their members list works and their group's history does not.
- **What is still open, named rather than implied:** there is no pagination beyond a limit; the feed has no filters (by kind,
  by member, by date); nothing records who accepted an invitation or who created one, so the feed cannot say; and the three
  older write boundaries - members, invitations, decisions - still take the caller from the body.

## Alternatives considered - and why rejected

**An `activity_events` table written by every operation.** It is what most products do, and it makes the feed one indexed
read. Rejected because it is a second copy of facts the rows already state: the membership knows when somebody joined, and a
log that disagreed with it would be believed rather than checked. It would also have to be written by every future slice,
which is a rule nobody can enforce.

**One `UNION ALL` query instead of three.** Tempting, and fewer round trips. Rejected because the three sources do not share
columns: a union would pad them into one shape and what makes each row what it is would become a null pattern rather than a
column - and the merge in memory is where the page's own order is stated once, in a function a test can call. Three reads
per page, each bounded by the limit, is the cost, and it is bounded by the page rather than the tables.

**Compose a `detail` sentence on the server.** One field, and every client shows it unchanged. Rejected for the reason RULE
B20 exists: the server cannot see a locale, so the sentence would be English in every language, and the wording of a feed is
the screen's business.

**Require a `groups` row.** It is tidier - every feed would be about a group that exists. Rejected because it makes this
read disagree with every other group read in exactly the state this deployment is in (ADR 0043), and the disagreement is
invisible until somebody opens a group that predates the table.

**A cursor now.** The contract asked for one. Rejected because a cursor over a union of three growing sources needs a key
that stays stable as rows are added, and neither `joined_at` nor `decided_at` nor `created_at` is that key on its own. The
slice that adds pagination gets to decide it, with a real client asking for page two.

**The check happens before anything about the group is read.** The group read narrows inside its query, so it cannot answer
about a group the caller is not in; this one cannot do that, because its three sources do not contain a group's members.
So the ordering of the two statements is the guarantee, and it is visible in the service rather than implied by a query.
