# ADR 0042 - A token exists before anything requires one

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`RequestSignIn`, `VerifySignIn`, `RefreshSession`, `ReadCurrentUser`, `UserAccounts`,
  `OtpChallenges`, `Sessions`, `OneTimeCodes`, `V106__identity.sql`, `AuthController`, `MeController`, `BearerToken`),
  `apps/web` (`shared/session`, the bearer header and one-shot renewal in `shared/api/client.ts`, `entities/identity`,
  `features/sign-in`, the account area in the shell), and the contract: `GET /me` and the three auth operations
  described, `RefreshRequest` added, `bearerFormat: JWT` removed, `429` given a body; 12 API tests, 4 unit tests,
  4 browser tests
- **Related:** ADR 0020 (the body decides who answered - the gap this narrows), ADR 0035 and 0038 (identity as the
  largest recorded gap), ADR 0041 (the slice before it, and the same "the contract's shape is the reason" move);
  RULE B15, B17, B20, B21, C1, C3, F1, F5, F8; P9

## Context

`GET /me` and the three auth operations had been in the contract since the first slice with nothing behind them, and
ADR 0038 recorded them as the largest gap in the document. Three other slices had already worked around the absence:
a decision's `memberId`, an obligation's `debtorId` and an invitation's `userId` are all "whoever the body says", and
ADR 0020 wrote that down as the shape of the gap rather than pretending otherwise. The client's member-facing screens
had no way to know which member a person is, so they fell back to showing a uuid where a name belongs (ADR 0035).

## Decision

**Sign-in is a phone number and a code, and verifying a code is registering a person.** There is no separate sign-up: the
code proves the number, so a number nobody has seen before becomes an account in the same statement that would have found
it, and `users_phone_numbers_are_unique` settles the race two verifications can have. The alternative - a `users` table
seeded by an admin operation - needs an admin, and there are no users to be one.

**A code is stored as a PBKDF2 hash with a per-challenge salt, and a session token as plain SHA-256.** The asymmetry is
the decision: a six-digit code is twenty bits, so a leaked table of plain codes is every live code guessable by hand, and
the cost per guess is the only thing standing between a leak and somebody's phone number. A token is 256 random bits -
there is nothing to enumerate, so a slow hash would buy nothing and cost every authenticated request. Code lifetime is
five minutes and a challenge dies after five wrong attempts; both are constants next to the algorithm rather than
properties, because a cost a deployment can lower is a cost that will be lowered.

**The code is never returned, never in a log line this module writes, and delivered through a port.** The operation
answers `202` whether or not the number is known - not distinguishing those two is what stops it being a way to ask which
numbers have accounts. Delivery is `OneTimeCodes`, whose adapter in this build writes the code to the log at `WARN` with
the number it was for: there is no SMS provider, so "delivered" means an operator can read it, and the level is chosen so
that a production deployment with this adapter in it shouts. That is the stand-in, recorded rather than implied.

**A refresh token has a body, because the contract gave it nowhere else to be.** `POST /auth/refresh` declares
`security: []` - "the bearer token does not authenticate this" - and declared no body. Interpreting the `Authorization`
header as an access token on some paths and a refresh token on others would make one header mean two things depending on
the URL, so `RefreshRequest` was added to the contract instead.

**Rotation replaces both tokens in one conditional update, and never moves the session's end.** The row is found by the
old refresh token *and* an unclosed window, and only the two hashes and the access expiry are rewritten: the refresh
expiry is carried over by not being touched, which is the only way a rotation cannot extend a session. The consequence is
deliberate and asserted: a replay of the previous refresh token finds no row, and the *old access token* stops working
too, because the row it was found by was rewritten.

**Tokens are opaque, so `bearerFormat: JWT` is gone.** A JWT needs a signing key and a story for rotating that key, and
`POST /auth/refresh` needs a row to rotate either way - so statelessness would buy one indexed lookup in exchange for a key
management problem this deployment does not have. The contract's schema for the scheme says what the token is now.

**`kycTier` is `none` for everybody, and that is stated where it is served.** No provider has established a tier for
anybody; the schema requires the field, and its description says the value means "not established" rather than "established
as nothing". A stored tier column nobody ever wrote would read as a decision somebody made.


## Consequences

- **Serving four operations means deleting four entries.** `ContractCoverageTest` now reports thirty operations,
  twenty-two served and eight recorded - labels, activity, groups, execute, void, webhooks and the `POST /fx/quotes`
  removal candidate - and the largest gap the document had is gone.
- **The client's member-facing screens can name the person now**, and what remains is the other half of ADR 0035: the
  screens still fall back to a uuid when a member catalogue is empty, and matching a session's user id against a group's
  members to say "this is you" is client work this slice did not do.
- **A `401` from the client means the session is over, because the transport renews first.** `shared/api/client.ts`
  retries once with a fresh pair when a request is refused *and* a session is stored - once, deliberately, because a loop
  would hammer an endpoint that answers `401` for a token that is already gone. A refused renewal forgets the pair, so the
  client stops sending a credential it knows is dead.
- **Two client bugs were found by the browser tests, both worth more than the tests cost.** A `202` with an empty body made
  `response.json()` throw, which the screen reported as "the server could not be reached" - a lie about a request that
  worked, now fixed by reading the body instead of trusting `content-length`. And `queryClient.clear()` after a successful
  sign-in removed the shell's `GET /me` without refetching it, so the header kept offering a sign-in link; invalidating
  does what the sentence "every cached answer is in doubt" was meant to buy.
- **The test every other slice now owes: `DatabaseTest.resetSchema` was missing tables.** This slice's tests count global
  rows, which is how `invitations` was found leaking between test classes a slice after it was added. The method now names
  the pattern rather than apologising for it.
- **What is still open, named rather than implied:** no sign-out operation (`DELETE /auth/session` would be a contract
  change), no reuse *detection* for rotated refresh tokens, no reaper for expired sessions and spent challenges, no device
  table, no KYC, no profile screen (so `displayName` is the phone number), no enforcement on any boundary but `/me`, and a
  session stored in `localStorage` rather than an `HttpOnly` cookie.

## Alternatives considered - and why rejected

**Enforce the token on every boundary in the same slice.** It is what the global `bearerAuth` declaration describes, and
it is the one thing that would make this slice feel finished. Rejected because it is not a line of code: every existing
caller would break at once, the client would have to sign in before it could read anything, and the API tests that seed
rows and call endpoints directly would all need sessions. That work deserves its own slice, its own tests, and its own
decision about what a signed-out client should see - and doing it here would hide all of it behind identity's arrival.

**Issue JWTs, as `bearerFormat` had always claimed.** Then the access token would need no lookup at all. Rejected because
`POST /auth/refresh` needs a row to rotate either way, so the lookup is not saved - and because a signing key needs a
rotation story, a storage story and a failure story that this deployment has none of. Removing `bearerFormat` was the
cheaper honesty.

**Return the code in the response, since there is no SMS provider.** It would have made the whole flow testable and
demo-able in one request. Rejected because it is a security hole that would have to be *unbuilt* the moment a provider
exists, and because the client would then have a code on screen - the one thing a code must never be. The tests seed a
challenge with the production hasher instead, which is the honest arrangement and the reason `OneTimeCodeHash` is one
class.

**Store the session in an `HttpOnly` cookie.** That is where a browser session belongs. Rejected as a *this-slice*
decision because it needs the client and API to be same-site, a CSRF story for the state-changing operations, and a
change to how every request is made - none of which exist. The trade is written down where the storage lives instead of
being quietly assumed: `localStorage` is readable by any script on the origin, which is why the access token is short-lived.

**One refusal per cause for a rejected code** - wrong, expired, spent, never requested. Rejected for the reason a sign-in
endpoint always rejects it: telling a caller which of those it was tells somebody guessing whether there is a live
challenge. One code, and an attempt counter underneath it that makes the answer affordable.

**Give `users` a `display_name` the caller supplies at sign-in.** It would avoid the phone number being shown as a name.
Rejected because the auth operations have no field for it, and because a name a person typed at a sign-in screen is a
profile, not an identity - the name that matters is the one a group records for them. It arrives with a profile screen.

**And the decision this slice is named for: the token exists, and nothing requires one yet.** `GET /me` reads it, and every
other boundary still takes what the body says. Enforcing the global `bearerAuth` declaration on the members write, the
invitation acceptance, the decision endpoint and the rest would break every existing caller at once - the client's screens,
every API test, and the worker's reads - and it is a slice of its own rather than a line of code smuggled in behind the
first token. What this slice buys is the thing that slice needs: a real credential, a real `401`, and a person the API can
name.


Four things in the contract fixed what this slice could be:

1. **`bearerAuth` is declared globally, with `security: []` on the auth operations.** So the document already said which
   operations take a token and which do not - and `bearerFormat` said `JWT`, which was a decision nobody had made.
2. **`POST /auth/refresh` had no request body.** With `security: []`, there was nowhere for a refresh token to travel at
   all: the operation could not be served as written.
3. **`POST /auth/otp/request` declared `429`** before anything served it - the one refusal in this document that existed
   as a promise with no code behind it.
4. **`User` requires `kycTier`**, and there is no KYC provider in this build.
