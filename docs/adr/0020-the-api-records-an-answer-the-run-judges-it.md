# ADR 0020 - The API records a member's answer; the run judges it

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`POST /v1/runs/{runId}/decisions`, `RecordDecision`, `Decisions`, `JooqDecisions`),
  `workers/settlement` (`RecordedDecisionsTest`), the OpenAPI contract's decision operation
- **Amended:** 2026-09-26 - the operation was `POST /v1/runs/{runId}/approve` and the types were named for approvals
  until a refusal became expressible; they are now named for the answer, and the request body's meaning is unchanged
  (ADR 0022)
- **Related:** ADR 0012, 0016, 0017, 0018; RULE B6, B17, S1; `docs/specs/settlement.md` section 4.1

## Context

**The largest gap in the product was that nothing could approve anything.** A run reaches `executing` only when
somebody answers it, the answering rule lives in `workers/settlement`'s `DecideOnRun`, and no surface called it -
so a cycle computed in a deployment waited for approval until somebody ran a test. Every other part of the path
worked: commitments were recorded, cycles computed, currency funded, transfers driven.

The OpenAPI contract had described the missing endpoint all along: `POST /runs/{runId}/approve`, "Approve a
computed run (quorum enforced server-side)", `200 Approval recorded`. It specified the operation with a path
parameter and no body - which cannot be implemented, because a quorum is a count of *members* and an approval by
nobody is not an approval. The contract had the right idea and no way to carry the approver, exactly as the rail
port's javadoc had promised a classification its signature could not express (ADR 0019).

Three facts shaped the decision:

- **The deployables never depend on each other (RULE B6) and there is no broker in this build.** They share a
  database, and one deployable already reads the other's tables as a projection: `apps/api`'s netting preview
  excludes commitments a settled run has discharged, by reading `settlement_runs` and
  `settlement_run_obligations`.
- **The ledger already has two writers.** `journal_entries` belongs to `libs/ledger`; `apps/api` appends a
  commitment's entry and `workers/settlement` appends a settlement's. The module that knows the fact writes it.
- **Quorum is a pure function over stored rows (ADR 0016).** `Quorum.counted()` intersects the answers with the
  run's own participants, and its javadoc says so explicitly: an approval from somebody the run does not concern
  "is reported and never counted". The rule is computed from rows rather than applied when a row is written.

## Decision

**1. The row is the interface.** `apps/api` writes the member's answer into `settlement_decisions`, the worker's
table, in the shape the worker's migration fixed: `(run_id, member_id, decision, decided_at)`. No intent table, no
message, no second state for the worker to consume. This works rather than merely being available because of the
third fact above: the answer's *meaning* is computed from the row, so a row written from another deployable is
judged by the same arithmetic as one written by the worker's own use case.

**2. The API records; it does not judge.** The endpoint contains no quorum arithmetic, no membership check and no
lifecycle check. It refuses exactly three things, each of which it can see from where it stands:

- the run does not exist (`404`, `api.no_such_run`) - there is nothing to answer;
- this member has already answered (`409`, `api.decision_already_answered`) - the primary key on
  `(run_id, member_id)` is the guard, so "one answer per member" holds for two requests arriving together and not
  only for one;
- the run's own rows refuse the answer (`422`, `api.decision_not_acceptable`) - the group's clearing account is a
  participant in a run's arithmetic and not a member of it, the schema says so, and this reports the refusal
  rather than translating a client's mistake into a `500`.

**3. Recording an answer for a run that is not ready for answers is legal.** A run still being computed can be
approved; the answer counts when the run reaches the point where answers matter. Refusing it would mean this
deployable re-deriving a lifecycle rule from a status column in another module's table, and duplicating a rule the
worker applies to its own callers.

**4. The word `'approve'` is written by the API's adapter and owned by the run.** `V502`'s check constraint fixes
the vocabulary and `Quorum` reads it, so the adapter writes a string it does not own - stated in the class rather
than discovered later.

**5. The contract gains the field the operation needed.** `requestBody: { memberId }` (required), a `200` schema
(`Approval`, with no `counted` field), and the `400`/`404`/`409`/`422` responses the implementation returns.

**6. The crossing is pinned by two tests, one on each side.** `DecisionApiTest` writes through the endpoint and
asserts the row's columns, the word and the instant; `workers/settlement`'s `RecordedDecisionsTest` writes *the
same statement* and asserts that the run's arithmetic judges it - that two answers recorded from the API settle a
cycle, that one is not a quorum, that an answer from a member the run does not concern counts for nothing, that an
answer written by the API and one written by the worker's own use case are the same arithmetic, and that the
run's rows refuse the clearing account. Neither module may depend on the other, so this is the closest thing to a
contract test that the arrangement allows - and it is why a schema change that broke the crossing would fail a
test rather than a deployment.

## Consequences

- **A cycle can now be approved and settled from a client.** `DecisionApiTest` records the answers and
  `RecordedDecisionsTest` proves that two of them, and nothing else, take a run from `awaiting_approval` to
  `done` with the movement booked.
- **The API knows one table it does not own.** The cost is real and bounded: the table's name and four columns
  are written down in `JooqApprovals`, `platform/persistence`'s owner map still assigns the schema to
  `workers/settlement`, and a column change is a change to a crossing that only two tests defend. The ledger has
  had the same arrangement since `V301`; this does not make it cheap, it makes it precedented and documented.
- **A member cannot yet read the run they are approving.** The contract's `GET /runs/{runId}` and
  `GET /runs/{runId}/transfers` are not implemented, so approving is uninformed until they are. That is the next
  slice rather than a footnote: a member's consent to a plan they cannot see is a product problem, not a
  convenience.
- **Rejections are not on this surface.** The endpoint is approve-only, and a rejection is not a symmetry: a
  rejection *ends the run*, and that transition is `DecideOnRun`'s. A rejection recorded by the API would leave a
  run waiting for ever with a rejecter on its rows - quorum is not met, so nothing executes, and nothing moves it
  to `rejected`. Supporting it needs a consumer in the worker (the thing this ADR argued is unnecessary for
  approvals) and a policy for answers that arrive before a run can act on them. That is a slice, and it is
  recorded as one rather than smuggled in here.
- **Identity is still client-supplied.** `memberId` is whoever the client says, exactly as an obligation's
  `debtorId` is. A quorum of client-supplied members is a quorum of claims, and this ADR does not pretend
  otherwise: it is the same gap the plan already carries for phase 3, now with one more caller.

## Alternatives considered - and why rejected

**An intent table in `apps/api`'s schema, consumed by the worker.** The textbook answer when two deployables
share a database, and the right shape the day an answer needs a lifecycle effect the API cannot express. It is
not needed for an approval: the judgement is a pure function over the row, so a consumer would copy a row it has
already decided the content of. The rejection case above is precisely the case that would justify it, which is
why rejections are deferred rather than approximated.

**The API calling the worker over HTTP.** Honest, direct, and it would make the two deployables depend on each
other at runtime - the boundary ADR 0018 drew, which exists so that a settlement worker can be restarted,
deployed or scaled without the API noticing. It would also require giving a non-web Spring application a web
surface to accept one row's worth of data.

**A message broker.** Not in this build, and one row in one table does not need one. Naming it matters because
"add a queue" is the reflexive answer to a cross-deployable write, and it would add an operational component
whose first message would be a row.

**The API writing the row *and* checking the run's state or membership.** The tempting version of "being careful"
that duplicates the run's rules in the module that does not own them: two implementations of membership, drifting
the day `group_members` exists, and a status check that would refuse an approval a member legitimately makes
early. The design of quorum is what makes restraint correct here rather than careless.
