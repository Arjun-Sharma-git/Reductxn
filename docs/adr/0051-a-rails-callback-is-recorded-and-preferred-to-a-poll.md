# ADR 0051 - A rail's callback is recorded, and the driver prefers it to a poll

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `POST /webhooks/rails/{provider}` in `RailWebhooksController`, `AcceptRailCallback` and its use case,
  `RailCallbackSignature` (HMAC-SHA256 over the raw bytes), `RailCallbackRefusals`, the `RailCallbackInbox` out-port and
  its jOOQ adapter, `reductxn.rails.webhook-secret`, the contract (description, request body, the signature header,
  `202` receipt, `400`, `401`) and `RailWebhookApiTest`; on the worker's side `V513__rail_callbacks.sql`, the
  `RailCallbacks` out-port read by `ExecuteRunService`, and two tests in `DriveRunsTest`
- **Aligns with:** ADR 0008 (the rail), ADR 0027 (an unset deployment property ingests nothing rather than everything)
- **Related:** ADR 0050 (which served `execute` and recorded why this one's urgency had already expired), ADR 0020 (the
  two deployables do not depend on each other), ADR 0021 (one place decides a fact), ADR 0003 (OpenAPI as the contract)
- **Closes:** the last entry in `ContractCoverageTest`'s `RECORDED_GAPS`, which is now empty

## Context

ADR 0046 left two operations standing. ADR 0050 served `execute` and, in doing so, recorded that the *reason* given for
keeping this one had already stopped being true: the argument was that `execute` and the webhook were two halves of one
mechanism, and building either alone "leaves money in a state nothing can resolve". The polling driver resolves a silent
rail with no callback at all, so that had been false since the driver grew its poll.

What is left is smaller and real:

- **A callback is faster than a poll**, and it costs the rail nothing to send.
- **A rail can only reach a web surface**, and this repository's only web surface is `apps/api`. The worker that owns
  transfers has no HTTP at all - it is a non-web Spring Boot application (a fact MASTER-PLAN already records, from the
  slice that found the worker exiting at startup).
- **A callback has to be authenticated somehow.** It comes from a machine: no member, no session, no token. What a rail
  and a deployment can share is a secret, and this deployment had nowhere to keep one - which is a configuration fact
  rather than a reason to keep the promise unbuilt.

## Decision

**A callback is recorded where the driver looks, and the driver prefers it to asking the rail itself.**

- **`apps/api` receives it**, because it is the only deployable a rail can reach, and the contract opts it out of the
  global bearer scheme. It is the first boundary in this module that no person uses.
- **Authentication is an HMAC-SHA256 signature over the exact bytes of the body**, `X-Rail-Signature: sha256=<hex>`,
  keyed with `reductxn.rails.webhook-secret`.
- **The body is verified before it is parsed, which is why the controller takes bytes rather than a bound DTO.** A
  signature covers bytes; binding first would mean the body had already been parsed, converted and walked before
  anything established who sent it - and a request this deployment does not trust would be answering questions about its
  own contents. The test that pins this presents *the same malformed body twice*: signed, it is a `400`; unsigned, it is
  a `401`.
- **An unset secret refuses every callback.** A deployment that has not been told what its rail signs with cannot tell a
  callback from anybody else's POST, so it says `401` for all of them. That is ADR 0027's shape (`reductxn.rates.feed`
  unset ingests nothing rather than everything) applied to the door a machine knocks on.
- **The row is written into the worker's own table** (`settlement_rail_callbacks`, `V513`), keyed by the reference, which
  makes idempotency a property of the schema rather than a check somebody has to remember: a redelivery - and every rail
  redelivers, because a callback is an at-least-once signal - replaces the rail's word about that transfer instead of
  adding a second opinion about it. This is the third table the API writes and the worker reads (ADR 0020, 0050).
- **The driver prefers a delivered answer to a poll** (`ExecuteRunService.askTheRailForIt`). A stored callback is turned
  back into `PaymentRail.RailStatus` and handed to the *same* `record(...)` path a polled answer takes, so the money's
  state is still decided in exactly one place (ADR 0021) - what changes is that a rail which has already answered is not
  asked to answer again, and the answer costs one indexed row instead of a network call.
- **A callback about a reference nothing was offered under is accepted and inert.** It is not refused: the API would have
  to read the worker's transfers to know, and an inert row is the worst a *signed* callback can achieve. The worker's
  test asserts the half that matters - a row cannot resolve a transfer the rail was never asked about, so nothing able to
  write one row can claim money moved.
- **A state this build does not know is refused with a `400` naming it**, rather than stored. The driver maps a stored
  word to a transfer's state exhaustively, so a word it could not map would be an answer that reached the rows as a
  question.

## Consequences

- **The recorded-gap list is empty, and the milestone has a cost worth stating.** `ContractCoverageTest`'s
  `RECORDED_GAPS` now reports nothing, because every operation the contract promises is served. What that costs is that
  one of the check's three directions has nothing left to fail on: "a recorded entry whose handler has since landed
  fails" can only be trusted because it was made to fail while the list was still being emptied (ADR 0038), and nothing
  in a build exercises it now. The other two directions are the ones that will keep earning their place - a new promise
  with no handler still fails, and a handler the contract does not declare still fails - so the empty map is the state
  this gate was built to reach rather than a reason to stop running it.
- **The callback's vocabulary exists in four places, and each copy earns its keep.** `V513`'s `CHECK` is the last line
  against a row no writer should produce; the contract's `enum` is the promise a rail generates types from; the API's
  `KNOWN_STATES` is what turns an unknown word into a readable `400` instead of a failed insert; and the worker's
  adapter switches over the same four words exhaustively to decide a transfer's state. Two of the four are not Java, so a
  single copy is not available - and the one alternative that would avoid the duplication (store whatever arrives and
  hope) is worse than all four, because the driver would then have a row it cannot act on and no way to say so.
- **A new table is not one place, it is three, and the third one bit immediately.** Adding `settlement_rail_callbacks`
  meant the migration, the worker's suite's reset list and this API's. I forgot the first two, and the symptom was not a
  failure in the new tests - it was an *unrelated* one: a callback is keyed by a reference the plan *derives* (RULE S2),
  so the same run produces the same reference in every test, and a row left behind by one test was found by another,
  which then resolved a transfer it expected to have to poll. The lesson is narrower than "remember the reset list": with
  derived identities, leaked state does not look like leaked state - it looks like a different feature behaving oddly.
- **One secret for the deployment, and the provider in the path is recorded rather than authenticated.** A rail's
  `{provider}` is written into the row because an auditor asks where a callback came from, and it is *not* checked,
  because there is one rail per deployment (`reductxn.rails.provider`) and therefore one secret. A second rail would need
  a map from provider to secret, and that is a change to make when there is a second rail rather than a shape to guess at
  now - recorded here so the gap is visible rather than implied by the path parameter's presence.
- **What this does not do.** It does not make a transfer's state reachable within the request that started it: the
  worker still acts on its own pass, so a callback is a head start rather than a synchronous settlement. It does not
  remove polling: a rail that never calls back is still asked, on the driver's cadence, exactly as before - which is
  what makes this a latency improvement rather than a new dependency of the money on a second mechanism.
