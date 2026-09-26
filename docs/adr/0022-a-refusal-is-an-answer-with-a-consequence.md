# ADR 0022 - A refusal is an answer with a consequence

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`POST /v1/runs/{runId}/decisions`, `DecisionRequest.decision`, `RecordDecision`,
  `Decisions`), `workers/settlement` (`ExecuteRunService.recordTheRejection`, `Quorum.countedRejections`), the
  OpenAPI contract's operation and schemas
- **Related:** ADR 0016 (and its amendment), 0018, 0020, 0021; RULE S1, S3, B6, B20

## Context

ADR 0020 gave a member a way to say yes. The worker has judged both answers since it was written -
`DecideOnRunService` records the decision and moves a rejected run to `rejected`, `Quorum` carries `rejecters`, and
`ExecuteRunService` refuses any run whose decisions include a refusal - but nothing could produce a refusal through
the product, because the API's one answer path wrote the word `'approve'` and its operation was
`POST /runs/{runId}/approve`. The vocabulary, the state and the gate existed; the surface did not.

A group that disagreed had no way to say so. Its run sat in `awaiting_approval` for ever: the driver reported a
refusal, an operator could see the stall, and the members could not close the cycle or record why. Silence looked
exactly like undecided.

Three questions had to be answered to close it.

**What is the operation called?** A path named `/approve` that records refusals is a lie in the one place a client
reads first. Nothing is released, so renaming costs nothing but the decision to be honest.

**Who owns the word?** The vocabulary is the run's: `V502`'s check constraint fixes it and `Quorum` reads it. ADR
0021 point 4 drew the line for reading (the API passes words through rather than declaring its own enum). Writing is
different: this module is the only writer, so it could hold a Java enum of two values and validate against it.

**Who writes the run's fate?** ADR 0020 left a rejection's *state* to the worker, and the worker's own
`DecideOnRunService` writes it - but that use case is not the one the API's row goes through. An out-of-band
refusal therefore left a run reading `awaiting_approval` while its own rows said the group said no. The refusal was
safe (execution's gate reads the decisions, not just the status) and the status was wrong for ever, which was
visible to members through the read endpoint the previous slice added.

## Decision

**1. The operation is named for the answer: `POST /runs/{runId}/decisions`.** The request body keeps its meaning -
an absent `decision` field is an approval - so a client that sent the old body still sends what it meant, and the
change is in the path and the response rather than in the request. Ports and types follow the same word
(`RecordDecision`, `NewDecision`, `RecordedDecision`, `Decisions`, `DecisionResponse`): the code was narrower than
the decision ADR 0020's title already stated ("the API records a member's answer"), and the second answer is what

## Consequences

- **A group can say no, and the run ends.** Through the product, for the first time: a member refuses, the row is
  recorded, the run becomes `rejected` on the worker's next look, nothing moves, and the group can open another cycle
  over the same commitments. A cycle where the group disagrees is now a closed cycle with an answer rather than a run
  that waits for ever.
- **A refusal is terminal for the run and not for the group.** The rejection is stored against the run; the
  commitments are untouched, because nothing of the plan was ever applied - RULE S3's point in the other direction.
- **Two paths write the same status.** `DecideOnRunService` when the worker's own use case records the refusal, and
  `ExecuteRunService` when the gate finds one written from outside. Both are one `UPDATE` of one column, both are
  guarded by the same `Quorum.rejected()`, and the second exists because the first cannot see a row it did not write.
  A reader of `settlement_runs` cannot tell which path produced the state, which is the point.
- **A refusal recorded from outside is not instantly visible in the status.** It is visible instantly in the
  decisions and in the read endpoint's `answers`, and the status follows on the worker's cadence. The safe direction
  is immediate (execution refuses on the decisions), so the window is a reporting window rather than a risk window -
  and it is the same window every other gate has.
- **A stranger's refusal is recorded and inert.** Which means a member of a group can be told "your refusal is on the
  record" while the run stays waiting, when the member's id was not one the run concerned. That is the correct
  reading of a client-supplied identity, and the honest fix is identity rather than a wider rejection rule.

## Alternatives considered - and why rejected

**Keep the path and add the field.** `POST /runs/{runId}/approve` with `decision: reject` would have been a smaller
diff and would have kept one endpoint. Rejected because the name is what a client developer reads first, and a path
that records refusals while being called "approve" is a near-miss that gets copied into a frontend, a document and an
SDK before anyone notices. Nothing is released, so the rename is free today and expensive later.

**A separate `POST /runs/{runId}/reject`.** Two operations, two request shapes, one fact: the schema's vocabulary is
a single `decision` column with two values, so two endpoints would be two paths for one thing. The endpoint that
records facts should be as wide as the fact.

**A Java enum for the answer, validated at the boundary.** The tidier-looking option: `Decision { APPROVE, REJECT }`
with a deserializer, and an invalid word refused before the database sees it. Rejected for the reason ADR 0021 point
4 gives for the read direction and this ADR's point 2 for the write: the vocabulary already exists in one place (the
migration's check constraint, read by the run's quorum), a copy in this deployable could drift from it silently, and
the drift would show up as an answer recorded under a word the run's arithmetic does not recognise. Letting the row
be the authority costs one `422` and keeps one list.

**Have the driver write the fate.** The driver is the reconciler of out-of-band rows (ADR 0018), so it could notice
`rejected()` before offering the run and write the status itself. Rejected because the driver's design is that it
decides nothing: it asks `ExecuteRun`, which owns every gate. A driver that wrote a status would be a second place
where "has this run ended" is answered - and the first place would still return a refusal, so the two could disagree
about the same run in the same pass.

**Have the API write the status.** The cheapest fix of all: the endpoint already knows the run and the answer, so it
could set `rejected` after inserting the row. Rejected because it is exactly what ADR 0020 forbids, and because the
API cannot decide it - whether the refuser was one of the run's participants is the run's arithmetic, so an API that
wrote the status would be moving a run on a client's claim. The read endpoint's test now asserts the absence of that
write, so the boundary is a failing test rather than a sentence.

**Ignore the stale status.** An operator sees the refusal in the pass report and the members see the answer in
`answers`; the run cannot execute either way, so nothing is *unsafe* about `awaiting_approval`. Rejected because the
status is the one field a member reads to know what is happening, and a state machine that says "waiting on your
group" about a group that has answered is a product that does not know what it is doing. It also keeps the run in
the driver's due query for ever, retrying a question that has already been answered.

made the difference visible.

**2. The word travels through; the vocabulary stays in the schema.** `DecisionRequest.decision` is a string with two
documented values, written straight into the column. A third word is refused by the check constraint and arrives as
the `422` this module already reports for a row the run would not hold - so there is no list in Java to keep in step
with the migration, and the answer to "what may an answer be" has exactly one home. An optional field with a
documented default is the wire format's business, so `absent means approve` is applied in the mapper and never
reaches the use case.

**3. A refusal's fate is written by the use case that owns the run's outcome, not by the driver.** The gate that
already refuses a rejected run now also settles it: when `Quorum.rejected()` is true and the stored status has not
caught up, `ExecuteRunService` writes `rejected` in its own transaction before answering the caller with the
refusal. The judgement stays in `Quorum` - no second opinion about whether the run was rejected, only a second
*reader* of the same one - and the driver keeps deciding nothing (ADR 0018). Two properties follow: the run leaves
the driver's due query (a terminal state is not offered again, so the retry stops), and the member-facing status
stops lying at the first look after the refusal.

**4. The API still writes no status.** The read endpoint's test asserts this explicitly (`awaiting_approval` after a
refusal was recorded) and the worker's test asserts the transition. That pair is the boundary ADR 0020 drew, now
tested in both directions: the API records facts, the worker moves the run.

**5. A refusal is read through the run's own membership.** `Quorum.countedRejections()` intersects the stored rows
with the run's participants, exactly as approvals were already intersected (ADR 0016, and its amendment: the rule had
not been applied to refusals, which let one row from anybody end a group's run while `memberId` is a client claim).
