# ADR 0030 - A run has to be findable before it can be answered

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (`GET /groups/{groupId}/runs`),
  `apps/api/src/main/java/io/reductxn/api/{port/out/SettlementRuns,port/in/ReadGroupRuns,port/in/RunReport}`,
  `application/runs/ReadGroupRunsService`, `adapter/persistence/JooqSettlementRuns`,
  `web/v1/GroupRunsController`, `apps/api/src/test/java/io/reductxn/api/ReadGroupRunsApiTest`;
  `apps/web/src/entities/run`, `apps/web/src/features/settlement`,
  `apps/web/src/routes/groups/$groupId/settlement/{index,$runId}.tsx`, `locales/en/settlement.json`
- **Closes:** the last step of the approval path - the contract has had `GET /runs/{runId}` and
  `POST /runs/{runId}/decisions` since ADR 0020 and 0021, and **no way to list a group's runs**, so the runs were
  addressable but not findable and nobody could walk the flow
- **Related:** ADR 0012 (per-currency figures), ADR 0020 (an answer is recorded, not decided), ADR 0021 (the API does
  not re-derive the run's arithmetic), ADR 0023 (gates), ADR 0028 (the client), ADR 0029 (the client's first write
  path); P8; RULE F4, F8, S1, S4

## Context

Three slices built the machinery for a member to answer a settlement run: the decision write path (ADR 0020), the run
report and its deliberate refusal to publish a quorum verdict (ADR 0021, then ADR 0023 when the worker started writing
one down), and the escalation and reconciliation reports. What none of them built was a way to *find* a run.
`GET /runs/{runId}` needs an identifier, and in this build the only source of one was the worker's command line - so
the approval flow existed, was tested, and could not be reached by a member.

The plan's route tree has said for several slices what the client should have here:
`groups/$groupId/settlement/index.tsx` ("runs list") and `$runId.tsx` ("who-pays-whom + tax saved"). The contract had
no listing endpoint at all, and the migration had already made the read cheap:
`settlement_runs_by_group (group_id, created_at DESC)` has been there since `V501`, unused.

## Decision

**1. A group-scoped list, ordered by the schema's own index.** `GET /groups/{groupId}/runs`, newest first by
`created_at`, ties broken by the run's id. The order is not a preference: the index exists for this read, and a plan
that appeared in a different order on two reads would make a page a member is working from unstable - the same
argument `V501`'s comment makes about transfer order.

**2. Rows are the full run, not a summary shape.** The alternative was a `RunSummary` with an id, a status and some
figures, and it is the wrong shape: two schemas describing one run are two descriptions that drift, which is the
second-implementation failure ADR 0021 spends its length refusing. A list row carrying only an id and a status would
also force the client to fetch each row to draw a page. So both endpoints go through the same `RunMapper`, and the
only difference is that the list does not carry `transfers` - which `SettlementRun`'s own comment says are served by
the endpoint that exists for them.

**3. A ceiling on the page, in the service.** `limit` defaults to 20 (the contract's default for list endpoints) and
is clamped to 100. The ceiling is a rule about this module's own reads - an unbounded read grows with a group's
history - so it lives in `ReadGroupRunsService`, while the wire default lives in the controller. Claiming paging
without a cursor would be a limit pretending to be paging, so there is no cursor yet and the contract says so.

**4. Two things the endpoint refuses to invent, both asserted.** There is **no `404` for a group nobody has heard
of**: groups are not tables in this build (an obligation's party ids are unverified for the same reason), so "no such
group" and "a group with no runs" cannot be told apart and the empty array is true of both. And there is **no `status`
filter**: newest-first puts what needs an answer at the top of any page, and a filtered query would need its own
ordering guarantee. Both are recorded as gaps rather than left as silences.

**5. The client's two screens, and what they are allowed to say.** The list shows when, where it stands, what it moves
per currency and how far off its gates are; the run shows the figures, the who-pays-whom list with the group's own FX
clearing leg named rather than hidden (ADR 0012), the answers on the record, and the answer form. Neither computes a
quorum: "1 of 3 approvals so far, so 2 short" is the worker's arithmetic projected from `settlement_run_gates`, which
is what ADR 0023 made possible and what ADR 0021 forbids the client from deriving.

**6. The catalog key is the status word.** `settlement.status.awaiting_approval` is exactly the word `V501`'s `CHECK`
constraint names, so the vocabulary's home stays the schema and a component asks for
`` t(`settlement.status.${status}`) ``. The model keeps only what a catalog cannot say - the badge tone - plus the
knowledge of which statuses exist, so a status this screen has not been told about renders "a state this screen does
not know" rather than an identifier. (The read model reports whatever the worker wrote, so a ninth state is a real
case rather than a defensive one.)

**7. The answer needs no idempotency key, no retry and no optimism.**

- **No key**: P8 asks a state-changing operation to be idempotent, and the contract achieves it by rule - a member has
  one answer per run, an answer is never overwritten, and answering twice is a `409`. A key would add nothing a
  constraint does not already guarantee, and unlike an obligation there is no second row to create.
- **No retry**: if an answer was recorded and its response was lost, pressing again answers `409
  decision_already_answered` and the screen stops offering the buttons. The failure a retry would hide is one the
  member resolves by reading the message.
- **No optimism**: the new answer is not what a member is waiting to see - the run's *status* is, and that is the
  worker's judgement over the answers rather than a row this client inserted. A provisional answer in the cache would
  be this screen claiming an approval counted, which is what the contract refuses to say (ADR 0020).

**8. The one refusal that changes what the screen offers.** `api.decision_already_answered` makes the buttons
disappear. The message stays the server's; the client only decides to stop asking, because keeping them would invite
something the system will not do.

## Consequences

- **The gap this closes was invisible from both sides.** The API had a read endpoint and a write endpoint for a run
  and no listing, and the plan's route tree had a runs list that nothing could fill; each looked complete on its own
  side, and the missing piece was only visible from the flow - a member, a plan, and no way to get from one to the
  other. The migration had even indexed for the read that never happened. A useful habit this recommends: walk the
  product's flows against the contract rather than the endpoints against their tests.
- **Playwright's `*` does not cross `/`, and the stub was silently letting a request through.** The settlement stub
  registered `**/v1/runs/*`, which matches `/v1/runs/{id}` and *not* `/v1/runs/{id}/transfers`; that request went to
  the dev server, answered something that is not a transfer list, and the screen showed an error where the test
  expected a table. The screen was right and the stub was wrong, and the test said "element not found" - which is the
  shape of a test failure that costs the most time. The fix is a `**` pattern plus one handler that dispatches on the
  path (`stubs.ts`), because "register the specific route after the general one" is an ordering nobody remembers.
- **A catalog key in a table is a key a gate cannot see, and the gate was right to fail.** The first version of
  `model/decision.ts` held `{ key: 'settlement.status.awaitingApproval', tone }` per status, so the component rendered
  `t(status.key)` and `I2` - which matches `t('...')` literals and template prefixes - could not see the keys at all.
  Rather than loosen the check, the design changed: the key *is* the status word, built from the status
  (`` t(`settlement.status.${status}`) ``), and the model keeps the tone. That made the vocabulary's home the
  migration's constraint and the keys visible to the gate - and it exposed the next finding.
- **A template prefix hides a wrong key, so `I2` passing is not the same as the app rendering words.** The catalog's
  status keys were written in camelCase (`awaitingApproval`) while the status word is `awaiting_approval`, so the
  screen rendered **the raw key** as a badge. `I2` passed, because the prefix `settlement.status.` makes every key
  under it count as asked-for; the check is deliberately coarse there (the alternative is failing on the one
  legitimate way to name a message per enum value), and the browser test is what caught it. The honest reading: a
  prefix-matched key needs a test that *renders* it, which is now the case for every status.
- **The fixture hit a constraint the schema had all along.** Two runs created at the same instant need two cycles of
  the group, and `settlement_cycles_are_unique_per_group_and_instant` refuses two cycles at one instant - so the
  ordering test's tie has to come from two cycles a second apart with the same run `created_at`. The run's own test
  comment records it, because the next person writing a fixture will reach for the same one-argument helper.
- **What the read model publishes decides what the client can show.** The gates carry `debtorsStillToConfirm` as a
  count rather than a list of members, so "two members who owe money still have to confirm" is a sentence this screen
  can say and "Asha and Bhavesh still have to confirm" is not. That is the read model's judgement about the run's
  participants (ADR 0021) rather than a shortage of effort, and the screen's copy is written to the limit.

## Alternatives considered - and why rejected

**A `RunSummary` schema for the list.** Rejected above on drift, and for a second reason: a member choosing which run
to open wants to see what it moves, so a thinner row would be a row the client has to enrich with one request per
line - a worse page and a second read of the same rows.

**A `status` filter on the list.** The natural "just the ones waiting for me" query, and the reason it is not here is
that a filtered list needs its own ordering and index story, and newest-first already puts what needs an answer at the
top of a page of twenty. Recorded as the first thing to add when a group has more than a page of history, together
with the cursor that page implies.

**Computing "one approval short" on the client.** It is one subtraction over `counted` and `required`, and the screen
could do it. Rejected because the counts are the worker's verdict (ADR 0023) and the moment a client derives anything
from them it is participating in RULE S1's arithmetic - the same reasoning that keeps the quorum verdict out of the
API. The screen says what the worker concluded, including when that conclusion is stale.

**Putting the answer form in a modal over the list.** Cheaper to build, and it keeps the list's context. Rejected
because answering is a decision about a *plan*: the figures, the movements and the gates have to stay on screen while
the member decides, and a modal that covers them asks for an answer to something the member can no longer see.

**Guessing the member instead of asking.** This build has no identity, and an answer names its member; the choice
could have defaulted to the first member in the group to save a click. Rejected because it would attribute a decision
to somebody who did not make it - and the server would refuse an answer by nobody anyway, so the screen would be
inventing convenience on top of a refusal.
