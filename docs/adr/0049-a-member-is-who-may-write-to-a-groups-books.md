# ADR 0049 - A member is who may write to a group's books

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (a description for `POST /obligations` where there was none,
  rewritten cycles paragraphs, `401`/`404` on both), `RecordObligation`/`OpenCycle` (a caller, and a `Result` the
  second one had argued against), `RecordObligationService` (membership, and the attribution), `OpenCycleService`,
  `ObligationsController`/`GroupCyclesController`, six API test classes, the e2e stubs and two specs
- **Aligns with:** ADR 0048 - which decided this rule and closed the two writes whose own descriptions named the gap
- **Related:** ADR 0042 (identity), ADR 0043 (`groups` is a table, with a creator), ADR 0045 (the void asks who is
  asking), ADR 0032 (a cycle is a claim, the worker computes it), ADR 0016 (why the decision endpoint stays out);
  RULE B15, B20, B21, C1, C3, L4

## Context

ADR 0045 made *voiding* an obligation a member's act. The two writes that create and close the same aggregate's
history did not ask, and both said so in their own way rather than by silence:

```java
Commitment commitment = new Commitment(
        obligationId, request.groupId(), request.occurredAt(), request.idempotencyKey(),
        // No identity module yet, so nothing can say who submitted it. Null means "system-generated"
        // on the entry; a made-up id would be a claim about a member who does not exist.
        null);
```

That comment was true when it was written and **false since ADR 0042**, which is what a recorded gap looks like two
years of slices later: the code still carries the reason it was excused, and the reason has expired. The cycles path
had the same shape in two other places - the port explained at length why it returned no `Result`:

> **No `Result`, and the absence is a claim.** ... Opening a cycle can be refused for nothing: the table's only other
> constraint is the pair, and a second request for a pair is not a failure but the same cycle again.

and the contract carried a paragraph that was already false:

> There is no `404` for a group nobody has heard of: groups are not tables in this build...

Groups have been a table with a creator since ADR 0043. Both arguments reasoned about the *row* - whether it could be
refused, whether it could be told apart - and neither about **who was asking**. That is the whole finding of this slice.

## Decision

**One rule, two writes, and it is the rule ADR 0048 already decided: a member of the group.**

- `POST /obligations` and `POST /groups/{groupId}/cycles` resolve the caller at the boundary, before the body or the
  path is judged, and ask `Groups.isMember` in the use case before anything is read or written.
- The refusal is the existing `api.no_such_group` (`404`): one answer for "no such group" and "not yours", so a guessed
  id cannot be tested by comparing two refusals (ADR 0044, 0045, 0047, 0048).
- **The obligation's membership check comes before the currency is resolved**, so a stranger cannot learn which
  currencies this deployment knows by comparing a `404` against a `422`.
- **The caller is attributed, not just authorised.** `Commitment.createdBy` stops being `null`: the member who recorded
  the commitment travels to the ledger as the entry's `createdBy`, which `EntryHash` already covered (L4) and which both
  `JooqJournalEntries` implementations already wrote to a column that already existed. No migration, and no second
  column beside the entry: the attribution is *inside* what the chain commits to.
- **`OpenCycle.open` gained a `Result`** and the paragraph arguing it could not have one is now the record of why it
  does - the same correction `AddMember` and `CreateInvite` needed in ADR 0048, for the same reason.

## Consequences

- **Three ports, three arguments, one mistake - and it is now a rule of thumb.** `AddMember` said "No `Result`, because
  nothing here can fail", `CreateInvite` said the same, and `OpenCycle` spent a paragraph proving it. Every one of those
  arguments was about the *row* being refusable and none about the *caller*. The rule this slice writes down: when a port
  documents why it cannot fail, check whether the reason is a property of the write or of the request - if it is the
  write, the caller can still refuse, and the shape has to carry that.
- **The ledger can say who caused an entry, and it needed nothing new to do it.** `Commitment.createdBy` reached
  `JournalEntry.createdBy`, which `EntryHash` already covered and both `JooqJournalEntries` adapters already wrote into a
  column that already existed. The `null` at the call site was never a schema gap; it was one argument that outlived its
  reason. Because the field is inside the hash, the attribution is covered by the chain rather than stored beside it - so
  an offline verifier recomputing the chain from its own rows reaches the same value (L4), which is exactly the claim
  this ledger is built to make.
- **A false paragraph and a missing one, both fixed with the code.** The cycles operation's description asserted that
  groups are not tables; `POST /obligations` had **no description at all** - the only write in the contract without one.
  Both are contract surface (RULE B20), and the first is worse than the second: a client that trusted it would conclude
  that a `404` was impossible, and a reader comparing the document with the code would have to guess which one was wrong.
- **The client needed no change, for the second slice in a row.** It has sent `Authorization` on every request since
  ADR 0042, so two more restricted boundaries cost zero lines of client code. What *did* change on that side is the
  stubs: both writes now refuse without the session's token, so the two specs that exercise them prove the token was
  sent, and `obligations.spec.ts` and `settlement.spec.ts` sign in.
- **Fixtures that had been using the write as a convenience became rows.** `ReadGroupCyclesApiTest` wrote its cycles
  through the endpoint; once opening became a member's act, a read test whose fixture had to join the group first would
  count its fixture in every assertion. It now writes rows, which is the pattern ADR 0048 established for
  `ReadGroupMembersApiTest` - the second application of it, and the reason it is worth stating as a pattern rather than a
  fix. The two FX read tests (`ReadReferenceRateApiTest`, `RateIngestionApiTest`) went the other way: their subject is
  the rate path, so they take a member caller and keep using the endpoint, because there the request *is* the thing under
  test.
- **What is still open, unchanged and named.** `POST /runs/{runId}/decisions` stays last: a stranger's answer is inert
  by the quorum's arithmetic (ADR 0016), so enforcement would change the status a stranger sees and nothing else. The
  `execute` + rail-webhook pair is the one remaining *mechanism* recorded as a gap. And every read is open to anybody
  holding a group's id - the pattern test, the members list, the balances, the runs, the cycles and the obligations list
  all serve a bare uuid, and closing that half is a product decision about how a group is shared, not an oversight.
