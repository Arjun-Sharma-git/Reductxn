# ADR 0021 - A read model projects a run's rows; it does not recompute its judgements

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/api` (`GET /v1/runs/{runId}`, `GET /v1/runs/{runId}/transfers`, `ReadRun`,
  `SettlementRuns`, `JooqSettlementRuns`), the OpenAPI contract's `SettlementRun` and `TransferInstruction` notes
- **Related:** ADR 0012, 0016, 0020; RULE B6, B16, B17, B20; `docs/specs/settlement.md` sections 4, 8

## Context

ADR 0020 closed the write half of consent: a member can approve a run. The read half was missing, and the contract
had described it from the beginning - `GET /runs/{runId}` ("status and headline numbers") and
`GET /runs/{runId}/transfers` ("the who-pays-whom instruction list"). Without them a member approves a plan they
cannot see, and the approve endpoint's own `200` is the only feedback they get: "recorded", which is true and silent
about whether the answer counted.

The rows needed are all in place, written by `workers/settlement`: `settlement_runs`, `settlement_partitions`,
`settlement_transfers` and `settlement_decisions`. `apps/api` already reads two of that module's tables as a
projection - the netting preview excludes commitments a settled run has discharged - so the direction is
established, and the two deployables still may not depend on each other (RULE B6).

What is *not* in place is the temptation. The contract's `SettlementRun` schema asks for things that are
judgements rather than rows:

- **quorum met?** - not a field, but the reason a member would want this endpoint at all: "have we approved yet".
  It is `Quorum`'s arithmetic in the worker: a majority of the run's participants, and every debtor personally.
- **`fxRequirements`** - "currencies the group must obtain from outside", derived by the worker from the plan's
  clearing residual (RULE S4).
- **`reportingTotals`** - headline figures in the group's reporting currency, which needs a reporting currency (a
  group's property, and groups do not exist yet) and a rate.
- **`executedAt`** - the instant a run reached `done`, which nothing records; `drivenAt` is when the driver last
  looked at the run, which is a different fact.

An API that filled these would be the second implementation of the run's arithmetic, and the two would disagree in
the window between a rule changing and both copies changing.

## Decision

**1. The projection reads rows and counts them. It does not compute what they mean.** Statuses, amounts, ids,
answers, and the number of transfers per currency are rows and counts of rows. Quorum, the external requirement and
the reporting totals are judgements over rows, and they stay in the module that makes them.

**2. The run's `status` is the answer to "what is happening next".** It is a column the worker moves when its gates
are satisfied, and telling `awaiting_approval` from `awaiting_fx_funding` distinguishes the two blockers a group
actually has: people, or currency. That is the useful granularity, and it needs no second arithmetic - only the
discipline to let a state machine answer a question instead of answering it here.

**3. The answers are rows, and none of them is marked as counted.** `settlement_decisions` is what a member needs in
order to check their own answer is on the record. Whether it counted is the run's judgement; a `counted` boolean
would be this deployable guessing, and it would be wrong exactly in the case the worker handles deliberately - an
answer recorded before the run was ready for answers, and an answer from a member the run does not concern
(ADR 0020).

**4. The words travel through unchanged.** The transfer and run statuses are read as {@code String} rather than
mapped into an enum of this module's own. The vocabulary is fixed by the columns' `CHECK` constraints, and a reader
that recognised only the states it had heard of would refuse to read a run from a worker that had learned a ninth -
a read model that breaks on upgrade. This is the mirror of the write direction: there, this module owns one word
(`'approve'`) and writes it into another module's table; here it owns none and passes them through.

**5. Two deplorables sharing a table is the arrangement; sharing a *type* is not available.** RULE B6 leaves no room
for a shared type across the boundary, so the shape is pinned by tests on both sides - the worker's own tests for
what it writes, `ReadRunApiTest` for what this module reads - and a column change that broke the projection fails
the test that reads it. The same argument ADR 0020 made for the write direction.

**6. The contract is corrected rather than filled in.** `TransferInstruction.tax` is no longer required: the worker
stores tax per currency (the plan's figure and what netting saved are partition columns, because the saving is a
property of netting rather than of any one payment), so there is nothing to put in a per-transfer field, and
splitting the partition's tax across its transfers would be this module inventing an arithmetic the worker never ran.
`fxLinkId` (a ledger fact about the entries a transfer books, not a column), `failureCode` (the row carries the
rail's own words, not a stable code) and the three run fields above are documented as unpopulated, with the reason,
so that a missing field reads as a decision instead of a bug.

## Consequences

- **Approving is no longer blind.** A member can read the plan's per-currency figures, the exact movements being
  asked for, whether a movement is a member's payment or part of the group's FX leg, each movement's state, and
  every answer on the record including their own. The two read endpoints and the approve endpoint are now one story:
  look, answer, check.
- **The API cannot tell a member "you are one approval short", and that is a real limit.** What it can say is the
  run's status and the answers as rows, and a member has to read the two together. Closing that gap honestly means
  the *worker* recording what its own arithmetic concluded - which is where the quorum is computed, and which this
  ADR does not pre-empt: it would be a stored fact rather than a recomputation.
- **`GET /runs/{runId}` answers `404` for an unknown run**, which the contract's `200`-only response list did not.
  A read of something that does not exist is not an empty success.
- **Two refusals moved.** `api.no_such_run` and `api.request_incomplete` became `api.no_such_run` and
  `api.request_incomplete`, because the second caller showed that neither is about approving - a code identifies the
  *condition*, not the endpoint (RULE B20), and two codes for one condition is a client branching on both. No code
  has been released, so nothing depended on the old names.
- **Three contract fields are known to be unpopulated** (`reportingTotals`, `fxRequirements`, `executedAt`) and one
  requirement was relaxed (`TransferInstruction.tax`). Each is recorded with its reason in the contract and in the
  schema's javadoc; the tests assert the absences so an addition has to be deliberate.

## Alternatives considered - and why rejected

**Compute what is missing here.** Quorum is a pure function over the run's rows (ADR 0016), so this module *could*
recompute it: read the positions, intersect with the answers, apply the majority rule and the debtor rule, and
publish `quorumMet`. It would be twenty lines and it would be wrong twice over - the rule would exist in two
modules, and the API's copy would be the one a member reads while the worker's is the one that moves money. The
whole point of ADR 0020's boundary is that one of those two gets to be authoritative.

**Ask the worker over HTTP.** The worker is not a web application, and making it one so the API can ask "is quorum
met" would couple the deployables at runtime - the thing ADR 0018's boundary exists to avoid, so that a settlement
worker can be restarted or redeployed without the API noticing.

**Store the judgement where the reader can see it.** The honest version of the same need, and the eventual fix for
"one approval short": the worker records what its gates concluded (as it already does for a refusal's reason). It is
a worker change with a migration and a decision about how stale a stored conclusion may be, which is why this ADR
names it as a next slice rather than pretending the read model can infer it.

**Fill the fields with what is easy.** `reportingTotals` could be approximated from any rate; `executedAt` could be
the last transfer's confirmation; `tax` could be split evenly across a partition's transfers. Each would be an
invented number in a field a client would treat as authoritative, which is worse than a missing field for exactly the
reason a missing field is honest: it says "nobody has answered this yet" instead of answering it wrongly.

## Amendment, 2026-09-26: the judgements now cross as a verdict

This ADR's honest limit was that the read model could report the rows and not the judgements - whether a quorum
exists, the group's external requirement, the reporting totals - and it named the eventual fix: the worker storing
what its gates concluded, rather than the API deriving it a second time. The alternative it rejected on the way was
the one that looked structural (share the rule as code), and the rejection is why the fix is a table.

That fix is now implemented for the two judgements a group can act on. `GET /runs/{runId}` carries `gates`, projected
from `settlement_run_gates`: whether quorum is met, with the approvals counted and required and how many debtors
still have to confirm, and the funding gaps per currency - plus the status the verdict was reached for, because a
conclusion outlives its run (ADR 0023). A member can now be told "one approval short" without the API implementing
the majority rule, and a run nobody has looked at yet reports no verdict rather than an invented one.

What remains unpopulated is unchanged and narrower: `reportingTotals` (the reporting currency is a group's property
and groups do not exist as tables), `fxRequirements` (the plan's *total* requirement - `gates.funding.outstanding`
is the gap, which is a different fact, and the difference is visible now rather than being one absence) and
`executedAt` (nothing records the instant a run reached `done`). Nor are the run's participants published, which is
why the verdict carries a *count* of the members still to confirm rather than their identities.
