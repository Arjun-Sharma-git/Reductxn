# ADR 0046 - A quote is locked by recording it, so the second door is removed

- **Status:** accepted
- **Date:** 2026-09-27
- **Implemented:** `contracts/openapi/reductxn-api.yaml` (`POST /fx/quotes` deleted, the `FxQuote` schema kept and its
  description narrowed, a comment left where the path was), `ContractCoverageTest` (its entry deleted and the count in
  its prose corrected), the regenerated client types
- **Aligns with:** ADR 0033 - which removed three operations for the same reason, and is the precedent this follows
- **Related:** ADR 0013 (a rate is locked, and the quote is stored on the obligation), ADR 0037 (a rate that cannot be
  locked is not a reference rate), ADR 0029 (a refusal is a value), ADR 0038 (every promise is either served or owned);
  RULE C1, C3

## Context

`POST /fx/quotes` has been in the contract since the cross-currency work, and it has never had a handler. ADR 0038
recorded it in `RECORDED_GAPS` with a note that said what it was: a **candidate for removal** rather than a screen.
That note also said the decision was owed - "this one should be decided, not owned" - because the list exists to make
"we will decide later" visible, and an entry whose text says "decide this" is a debt rather than coverage.

Two facts decided it, and both are properties of the code as it stands rather than opinions about the future:

- **The capability is implemented, and in the right place.** `RecordObligationService` takes an optional
  `settleInCurrency`, locks a quote with `FxQuote.lock` at the moment the commitment is recorded, stores it through
  `JooqFxQuotes`, and returns it on the obligation (`fxQuote`, `FxQuote` schema). Failing closed is implemented there
  too: a stale or missing rate answers `fx.rate_stale` / `fx.rate_unavailable` and nothing is written. `JooqOpenObligations`
  reads the quote back for the preview. So the operation in the contract was a *second entry point* onto machinery
  that already worked, reachable through the one call where the rate has meaning.
- **Nothing calls it.** No handler, no client call, no test: the only live references were the contract path, the
  `RECORDED_GAPS` entry and the generated types.

## Decision

**Delete the operation, keep the schema.**

A locked quote is created by the write that needs it, not requested beforehand. Recording a cross-currency commitment
is the instant a rate becomes contractual (ADR 0013), so offering a separate operation would let a client lock a rate
*before* any commitment existed - and this deployment has nowhere to put such a quote's lifecycle: no expiry, no
binding to a future obligation, no release. An unused locked quote is a rate risk with no owner, which is the failure
mode ADR 0013 point 5 exists to prevent, and there is no second door on the settlement path for the same reason.

What a client actually needs is already served, and it is served better: `GET /fx/rates` gives an **indicative** rate
for the member to look at while deciding (the recording form uses it and says "indicative" in the sentence a member
reads), and `POST /obligations` returns the **locked** quote that counts. Reading a rate before committing and having
one locked at the moment of commitment are two different questions, and the contract now answers each exactly once.

The `FxQuote` schema stays: the obligation carries it. Its description gained the sentence that says where a quote
comes from and why there is no operation that makes one, so somebody reading the schema does not conclude that the
endpoint is merely missing.

## Consequences

- **The contract is 29 operations, 27 served, 2 recorded with an owner** (`ContractCoverageTest` reports this, and
  its prose now says two rather than a number that can drift). The remaining two are `execute` and the rail webhook.
- **The coverage list grew a third exit.** An entry can leave it by being served, by being re-owned, or - now - by
  being decided out of the contract. The test's documentation records all three, because the third one is the one that
  looks like cheating and is not: the alternative was to build a door nobody needs in order to make a number go up.
- **A comment sits where the path was.** The removal is in an ADR, and an ADR is where a decision is written down,
  but the place somebody would re-add the operation is the contract - so the reason is there too, in the file's own
  voice. The comment is safe for the coverage test's parser, which reads paths by indent and verb, and a `#` line
  matches neither.
- **A drifted count in prose was found and fixed while doing this.** `RECORDED_GAPS`' documentation said "the four
  left when ADR 0044 served the activity feed" and had been wrong since the void slice made it three - nothing checks
  prose, which is the argument for stating the count as a fact about now rather than as a property of the file. Both
  that sentence and the historical "thirty operations" line in the class header were corrected rather than updated to
  a new number that would rot the same way.
- **`execute` and the rail webhook are deliberately left standing, and together.** They are two halves of one
  mechanism: `execute` submits a plan to a rail, the webhook is how the rail's answer arrives. Building either alone
  leaves money in a state nothing can resolve, which is worse than leaving both recorded as unbuilt work with an
  owner named. What `execute` needs before it can start is its own ADR: a run-state licence, a claim so two workers
  cannot execute one plan, and per-transfer outcomes - and the rail adapter it names does not exist in this
  repository, which is a fact rather than a scheduling decision.
