# ADR 0055 - A share link is the same code, granting a look

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (`POST /groups/{groupId}/shares`, `GET /shared/{code}`,
  `grants` on `Invite`/`InvitePreview`, `422` on both wrong-grant refusals, the `SharedView`/`SharedPosition` schemas),
  `V109__invitation_grants.sql`, `InvitationGrant`, `CreateShare`/`CreateShareService`,
  `ReadSharedView`/`ReadSharedViewService`, `GroupPositions`, `Invitations.record`/`previewOf`/`findByCode`,
  `InviteRefusals.NotAMembership`/`NotAShare`, `SharesController`, `SharedViewResponse`, `ShareApiTest` (9 tests),
  `entities/invite`, `ShareLinkCard`, `SharedViewScreen`, the shared route, the e2e stubs and four specs
- **Aligns with:** ADR 0054, which made a code the thing a group hands out and left this open; ADR 0039, whose "one
  computation" rule this slice had to keep while adding a second reader
- **Related:** ADR 0041 (an invitation is spent by joining somebody), ADR 0048 (a member is who may hand one out),
  ADR 0043 (the `groups` table arrived additively), ADR 0021 (one description of a thing), ADR 0012 (no cross-currency
  total); RULE B12, B15, B17, B20, C1, C3, F1, F4, F8, F10, I2, I3

## Context

ADR 0054 closed nine reads and made a code the artifact a group shares, because an id was never a credential. It also
listed what it did not decide, and the first item was this one:

> **Sharing with somebody who must not join.** An accountant, a landlord, a mediator: none of them should become a
> member, and a code is the wrong instrument for them. That is a capability link - a token scoped to a read, revocable
> independently of a membership - and it is a feature rather than a permission, because it introduces a principal that is
> not a user.

That sentence had already decided the important half by accident: it called the instrument "a capability link", and this
deployment has exactly one capability - a code that is unguessable (forty bits), expiring, and revocable by not making
another one. What it did not have was a way for a code to mean anything other than "join".

Two ways to read that gap, and the difference is the whole slice:

1. **A second kind of credential.** A `share_links` table, its own alphabet, its own window, its own token format, and a
   reader that authenticates with it. That is what "a token scoped to a read" sounds like when read literally.
2. **A second meaning for the same credential.** The store already answers every question a link needs answered: the code
   is the primary key and therefore globally unique, which is what lets a link carry its own group with no id in the URL;
   the window is a half-open pair of instants; the limit is optional and null means unbounded; a use is spent by a
   conditional update so two racing requests cannot take the last one. The only thing missing is a word saying what the
   holder may do.

The second is the smaller change and the more honest one, and the reason is what a join code's *rules* are: they are
rules about a bearer credential, not about membership. Splitting the table would have restated all five and then needed
its own version of whichever changed, which is the second description of "a code" that ADR 0021 exists to refuse.

There is also a disclosure argument for keeping them in one store with one word. A group's numbers are the thing a link

## Decision

**A share link is an invitation's code with `grants = read`, and it shows a group's positions to whoever holds it.**

- `invitations` gains `grants`, a word with a CHECK in `('membership', 'read')`, defaulting to `membership` so the
  backfill is the column's definition rather than an `UPDATE`. Both creation paths write it explicitly - the default
  exists for the migration, not for the code, because a creation that relied on it would change what it grants the day
  somebody edited the default.
- **Two operations, not one with a body.** `POST /groups/{groupId}/shares` makes a link; `POST /groups/{groupId}/invites`
  keeps making invitations and stays body-less. The choice between them is not a parameter, it is which audience the
  group is handing something to, and a body would have made membership the default.
- **The read is `GET /shared/{code}`, and it is `security: []`** like the preview: the person who follows a link an
  accountant was sent has no account here. It answers the group's label and member count, the instant the positions were
  computed at, the link's expiry, and one `SharedPosition` per member per currency - each carrying the balance read's
  entry plus the name the group recorded.
- **The refusal names the true condition, and this is the one place that departs from the sameness everywhere else.**
  `api.no_such_group` and `api.no_such_run` answer "does not exist" and "not yours" identically, because ids can be
  guessed and enumerable and telling conditions apart would let somebody learn which ids are real. A **code** is neither
  guessable nor enumerable, and the person asking *holds* it - so `422 api.invite_not_a_share` and
  `422 api.invite_not_a_membership` tell them what their own credential is for, and a client that read
  `InvitePreview.grants` never has to see either.
- **One composition serves both readers.** `GroupPositions` is the balance computation extracted from
  `ReadGroupBalancesService`, which keeps its membership check and calls it; `ReadSharedViewService` keeps its code check
  and calls it. The two reads differ in *who may ask* and in nothing else, which is exactly the split ADR 0039's rule
  demands - and it is asserted: `ShareApiTest` compares the shared positions with the balances read's, ids and amounts in
  order.
- **The policy has one home.** `InvitationPolicy` holds the seven-day window and the absent limit, because ADR 0055 gave
  them two callers: two copies of "seven days" is how a share link comes to outlive an invitation without anybody
  deciding it should.
- **The client shows it.** The members screen gains a card beside the invitation's (a link is the same act for a
  different audience), `/shared/$code` is a route of its own showing the positions with the names the server sent and
  the stamp, and the invitation screen refuses to offer a form for a read code - it links to the shared view instead,
  keeping the code the person was sent.

### What is deliberately not decided here

## Alternatives rejected

- **A `share_links` table with its own token format.** The literal reading of "a capability link", and the one that
  restates five rules about bearer credentials to gain one word. Rejected on ADR 0021's ground, and because the second
  table's rules would then be the ones to drift.
- **One creation operation with a body** (`{ grants: 'read' }`). Fewer operations, and it makes membership the default a
  caller can forget to change - on an operation whose description is built on taking no body at all. The two creations
  are two commands.
- **A boolean `is_share`.** It makes "share" the marked case of an unmarked default and cannot hold a third grant; the
  table's `role` column is the precedent for a word with a CHECK, and this one differs in that something actually reads
  it.
- **Serving the shared view from the balances endpoint with a code in place of a session** (`GET /groups/{id}/balances`
  accepting a link). Two credentials on one route is a reader that has to try both, and it would put a group id in a URL
  a link holder has no business knowing.
- **Including more in the view** - memos, the activity feed, the members list, the invitations. Each is a disclosure the
  group did not make by handing out a link; the positions with names are what "look at what we owe" means, and the
  absence is stated in the schema rather than left to be inferred.
- **Leaving names out and letting the client show identifiers.** The person holding a link cannot call the members read -
  that read is the group's (ADR 0054) - so a view without names would be a list of uuids and a number, which is not what
  an accountant asked for. The name travels with the position, from the same rows the group's own members read.
- **Refusing a wrong-grant code with the `404`-for-two-conditions answer.** Consistent with the group and run reads, and
  worse: it withholds a fact about the caller's own credential. See the Decision above.
- **Spending a use when somebody reads through a link.** A limit is about how many people a group lets in; reading is not
  joining, and a link that stopped working because it had been opened too often would be a link that broke by being used.
- **A second window for links** (say ninety days, because an accountant's quarter is long). Defensible on usability and
  rejected for now on reproducibility and on the revocation gap: with no way to stop a link early, a longer window is a
  longer exposure, and the option is recorded rather than taken.

## Consequences

- **The nine-read slice's rule now has one exception, and it is written down.** Scoped reads answer the truth about a
  code; id-scoped reads keep answering one code for two conditions. The two rules sit next to each other in the contract,
  each with its reason, because a reader comparing them would otherwise think one was a mistake.
- **A group's numbers can now leave the group deliberately.** That is the feature. It is also the risk, which is why the
  card states it, the schema's description states the absence, and the ADR states that revocation does not exist yet.
- **`GroupPositions` is the second time this module has extracted a computation to keep two readers honest** (the first
  was ADR 0039's "the balances read is the preview's computation"). The pattern is worth naming: when a second condition
  appears, move the *computation* down and keep the *condition* in the use case, so the difference between the two reads
  is one readable line.
- **`ShareApiTest` is nine tests**: the link reads without a session, a link lasts seven days, the positions are the
  balances read's own, a group with a row is named, an id the group does not name carries no name, a link joins nobody
  and says so, an invitation is not a link and says so, the preview says what a code grants, only a member may hand one
  out, and the two code states (unknown, expired). API tests went from 224 to 233.
- **The browser suite went from 58 to 62**: the shared view renders with no session at all (which is the assertion), a
  link that is an invitation says so, the members screen makes a link and says what it is handing out, and the invitation
  screen refuses to offer a form for a read code.
- **Two catalogue entries the error-code gate demanded** (`api.invite_not_a_membership`, `api.invite_not_a_share`): I3
  requires a sentence for every code the server can emit, which is the gate doing exactly what ADR 0052 said it was for.


- **Revocation.** A link stops working when its window closes, and this build cannot stop one earlier: the plan records
  the missing operation, and the card's own copy says so rather than letting a member believe the link is recalled when
  they make a new one.
- **Who may hand one out.** ADR 0048's rule unchanged - a member - and ADR 0056 records the question about the role word.
- **What else a code might grant.** A code that lets somebody answer a single run is the same instrument a third time,
  and the CHECK constraint and the enum are the two places that would have to change deliberately.
- **A window a caller chooses.** Still seven days for both kinds, for the reproducibility reason ADR 0041 gives; a
  quarter-long link for an accountant is a decision somebody has to make on purpose, and the plan records it.

shows, and a group should have to *choose* to show them: a separate kind of code makes that choice visible in a column,
where a flag on the creation operation (`isShare: true`) would make "join somebody" a default a caller could forget to
change - on an operation whose description is emphatic that it takes no body at all.
