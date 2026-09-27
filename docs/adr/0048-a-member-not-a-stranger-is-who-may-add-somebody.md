# ADR 0048 - A member, not a stranger, is who may add somebody to a group

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (both operations' descriptions and their `401`/`404`),
  `AddMember`/`CreateInvite` (a caller and a `Result`), `AddMemberService`/`CreateInviteService` (the condition),
  `GroupMembersController`/`InvitesController` (the caller, resolved first), `AddMemberApiTest` (nine tests updated, two
  added), `InviteApiTest` (fifteen tests updated, two added), the e2e stubs (both writes refuse without a token), the
  members and invites specs
- **Aligns with:** ADR 0047 - which closed the acceptance and named these two with the rule already argued
- **Related:** ADR 0035 (the members write exists because the read would be empty forever), ADR 0041 (the invite flow),
  ADR 0043/0045 (the caller is resolved before the work; a stranger learns nothing from which refusal they got);
  RULE B15, B20, C1, C3

## Context

Two operations described their own lack of a rule, in as many words:

> **No `admin only`, because there is no admin.** This summary used to claim "Add a member directly (admin only)", which
> is a check this build cannot make: there is no identity, so there is nobody whose `admin` row would authorise anything.
> The endpoint is unauthorised and says so... a summary promising a check that does not exist is worse than one admitting
> there is none.

> **Anyone can create an invitation, and that is the recorded identity gap rather than a policy.** There is no
> authentication in this build, so nothing here decides who may invite whom.

Identity arrived in ADR 0042, and ADR 0047 named these two - with the rule - as the next slice: *"should require a
member, which `Groups.isMember` already answers and which no operation asks yet"*. What the previous slice could not
decide, this one does not have to: the rule was argued before the code existed, which is the whole point of recording a
gap rather than papering over it.

## Decision

**A member of the group. One rule, two operations, the check the store already answers.**

- `POST /groups/{groupId}/members` and `POST /groups/{groupId}/invites` both resolve the caller at the boundary and ask
  `Groups.isMember` in the use case, before anything is written.
- The refusal is the existing `api.no_such_group` (`404`), not a new code: "no such group" and "not a group you are in"
  are one answer, so a guessed id cannot be tested by comparing two refusals - the reading the activity feed and the void
  already use (ADR 0044, 0045). A new code would be a second name for one fact, which RULE B20 exists to prevent.
- **Membership, not `admin`.** The role is recorded in `group_members` and nothing reads it yet. An admin-only rule would
  invent a privilege whose only effect would be to make this operation unusable for everybody the invitation flow
  creates - which is every member except the group's creator (ADR 0043). When a slice reads roles, the rule can tighten;
  inventing it now would be a check with no user.
- **The caller is resolved before the body or the path is validated**, so a request with no session is a `401` even when
  it is otherwise malformed. That is the order at every boundary that needs a caller (ADR 0043, 0045, 0047) and the
  reason is that "who is asking" is a question about the request, not about its contents.
- **The invitation is minted after the check.** A refused creation leaves no code behind for somebody to find, which the
  test asserts by counting the table rather than the response.

## Consequences

- **The ports changed shape and the stores did not, which is the same finding as ADR 0047 one layer over.**
  `AddMember.add` and `CreateInvite.create` gained a caller and a `Result`; `GroupMembers.add` and `Invitations.record`
  are untouched, because a membership and a code are still things nobody can refuse. `AddMember`'s own documentation said
  *"No `Result`, because nothing here can fail"* - an interface that had recorded its own innocence - and that paragraph
  is now the record of what changed and why the change came from outside the write rather than inside it.
- **The client needed no change at all.** It has sent `Authorization` on every request since ADR 0042, on the reasoning
  that *which* paths need a token is the server's decision - so two boundaries became restricted and forty lines of
  client code did not have to know. That decision was made for a different reason and paid here.
- **The stubs now refuse, and the specs sign in.** The e2e members and invites stubs answer `401` to a write without the
  session's token, mirroring the server, so the two specs that exercise those writes prove the token was sent. One new
  browser test says what a signed-out visitor sees instead: the refusal, with the member list still rendered, because the
  read was not restricted.
- **A fixture that satisfied a precondition changed the subject of eleven tests, and the suite caught it.** The first
  version of the invite fixture made the *invitee* a member of the group so that she could create codes - and every
  acceptance of hers then answered "the group already has you", which is a `200` where the test expected `201`. The rule
  is worth writing down: the **creator** is who the condition is about and the **invitee** is who the invitation is for,
  and a fixture that makes them the same person stops testing joining. It was caught because the assertions were specific
  about status rather than about success.
- **Assertions moved from positions to predicates.** The member list now holds the caller as well as the person the test
  added, so `$[0]` would have made several tests depend on the order two memberships were written in. The ones that pin a
  count assert about the person they are about, through a JSONPath predicate or a `WHERE user_id = ?`.
- **What is still open, named.** `POST /obligations` and `POST /groups/{groupId}/cycles` take their actor from the body,
  and the rule ADR 0047 argued (a member of the group) applies to both. `POST /runs/{runId}/decisions` stayed last
  because a stranger's answer is inert by the quorum's arithmetic (ADR 0016) - enforcement would change nothing except
  the status a stranger sees. *(ADR 0053 has since served it, and that reason turned out to be half true: the answer is
  inert to the quorum and not inert as a row, because the group's activity feed shows it. The sentence above is kept as
  written rather than rewritten, and the correction is in 0049's amendment and in ADR 0053.)* And every read is still open to anybody holding an id: the obligations, members, balances
  and runs reads serve a bare uuid, the home screen's paste-a-group-id flow depends on it, and closing that half is a
  product decision about how a group is shared rather than a gap nobody noticed.
