# ADR 0047 - The joiner is the caller, and the contract's own promise is what closed it

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (the acceptance's description, the `AcceptInvite` schema, the
  `bearerAuth` note), `InvitesController` (the acceptance resolves the caller), `AcceptInviteRequest`,
  `AcceptedInvitation` (documentation), `InviteApiTest` (eleven tests updated, two added), the client's accept model, its
  test, the screen and the English copy, the e2e stubs and specs, and a signed-in fixture hoisted into `DatabaseTest`
- **Aligns with:** ADR 0042 - which made a token exist, and whose own description called this the recorded next step
- **Related:** ADR 0020 (the body says who the member is - the stance being retired here), ADR 0041 (an invitation is
  spent by joining somebody), ADR 0043/0045 (the caller is resolved before the work), ADR 0033 (a promise the code does
  not keep is worse than no promise); RULE B15, B21, C1, C3, I2

## Context

The contract described its own identity gap in five places, and one of them was a promise with a condition on it:

> **The body says who is joining, because nothing else can.** There is no `GET /me` and no authentication yet... When
> sign-in exists, this body disappears and the acceptance becomes about the caller.

Sign-in exists (ADR 0042). That paragraph was therefore false in two ways at once by the time this slice started: it
described an absence that is no longer an absence, and it promised a change that had not been made. `AcceptInvite`'s
schema said the same thing in the same tense - "this build has no sign-in, so the caller says who they are and the server
has nothing to check it against" - and the sign-in screen had been signing people in for three slices.

The other four places the gap is named were *not* promises, and this slice deliberately does not touch them: the members
write says it is unauthorised "because identity is the recorded gap, and a summary promising a check that does not exist
is worse than one admitting there is none", the invites write says "anyone can create an invitation", the decisions
endpoint says the member is whoever the body says, and the `bearerAuth` scheme notes that only `GET /me` reads the token
today.

## Decision

**Close the gap in the one operation that promised to close itself, and derive the rule from what the operation means.**

Accepting an invitation joins *you*. A code is a thing somebody is **given**, and handing it in is the act of using it -
so the joiner is whoever holds the session, which is the only reading under which a member cannot spend an invitation on
somebody else's behalf. That is ADR 0041's own title as a rule: an invitation is spent by *joining* somebody, not by
asking on their behalf.

What the slice deleted, in order:

- `userId` from the request body. `AcceptInvite` now carries `displayName` and nothing else: the name is what the group
  will call them, and the person is the session's business.
- `newMemberId()` from the client's accept model, and the sentence in the English copy that told a joiner to keep an
  identifier "because there is no sign-in to recover it from". The screen now says which account they are joining with,
  because that is what the group will record.
- The `userId` validation at the boundary, and the notion that a malformed one is a `400`.

Two smaller decisions came with it, and both have a test rather than a sentence:

- **A `userId` still present in the body is ignored, not refused.** The caller cannot name anybody else, so the field has
  no effect to protect against; a `400` would break a client that still sent one while protecting nothing. The
  `AcceptInvite` schema says so, and `theJoinerIsTheSessionAndAUserIdInTheBodyIsIgnored` sends Boris's id with Alice's
  token and asserts that Alice joined.
- **The caller is resolved before the body is validated.** A request with no session is a `401` even when its body is
  incomplete, which is the order the three group operations and the void already use (ADR 0043, 0045): who is asking is
  the first question, not the last.

## Consequences

- **`AcceptInviteService` did not change at all, and that is the finding.** It already took `accepted.userId()` and used
  it as the member; the only thing that was wrong was *where the value came from*. The gap was one layer out - at the
  boundary, where a body was standing in for a session - and a slice that had gone looking for it in the application
  layer would have found nothing to fix and might have concluded the gap was not real.
- **The `bearerAuth` description is truer, not just longer.** It said "only `GET /me` reads the token today: enforcing
  the declaration on the other boundaries would break every existing caller at once". Six boundaries read a caller now
  (`/me`, the three group operations, the void, the activity feed, and this acceptance), so the note says which are done
  and which are not, because a security scheme whose description is stale is a page a reader stops trusting.
- **A fixture that had been copied three times was hoisted.** `GroupsApiTest`, `VoidObligationApiTest` and
  `ActivityApiTest` each carried a private `signedIn(phone)` - the same session row, the same hashing, the same windows -
  and this slice would have made it four. It now lives in `DatabaseTest` with the reason written down, and the e2e
  `signIn` helper moved to `stubs.ts` for the same reason. This is the mistake ADR 0045 recorded (*"a duplicated mapping,
  deliberately"*) being avoided rather than repeated: the duplication there was honest because the two conversions
  answer different questions, and this one never was, because a credential is one rule.
- **Two new API tests and one new browser test.** Signed-out acceptance is a `401` that spends nothing and joins nobody;
  a body naming somebody else joins the session; and in the browser, an acceptance with no session shows the refusal and
  stays on the form. The browser accept test now signs in first and asserts the exact body sent, `{"displayName":"Alice
  Rao"}`, so "the body no longer says who" is a fact the suite checks rather than a comment it repeats.
- **The remaining identity gaps are still gaps, and this is what they are.** `POST /groups/{groupId}/members` and
  `POST /groups/{groupId}/invites` should require a *member*, which `Groups.isMember` already answers and which no
  operation asks yet; `POST /obligations` and `POST /groups/{groupId}/cycles` take their actor from the body for the same
  reason; `POST /runs/{runId}/decisions` is deliberately last, because a stranger's answer is already inert by the
  quorum's arithmetic (ADR 0016) and enforcing it would change nothing about the outcome. None of the reads are enforced
  either - and that is a decision rather than an oversight, because the home screen's paste-a-group-id flow is a read
  that has never needed to know who was asking (ADR 0043).
- **One flake was seen and chased rather than ignored.** The first full browser run failed `settlement.spec.ts:63`
  ("Waiting for answers" not visible) and interrupted six tests behind it; those two spec files then passed 18/18 alone
  and the full suite passed on the next run. It is recorded as an observation with its evidence, because a suite that is
  green twice out of three is not the same claim as one that is green.
