# ADR 0037 - A rate that cannot be locked is not a reference rate

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `GET /v1/fx/rates` in `apps/api` (`ReadReferenceRate`, `ReadReferenceRateService`,
  `FxRatesController`), `FxRateResponse` hoisted out of `FxQuoteDto` so one type is the contract's `FxRate` schema
  everywhere; the contract's response list for that endpoint was extended with the two failures it can actually answer
- **Related:** ADR 0035 (the audit that found this read unserved), ADR 0036 (the other two reads, and the shape this
  one follows), ADR 0013 point 6 (fail closed rather than guess a rate), ADR 0021, ADR 0027 (where rates come from);
  RULE B12, B20; P8

## Context

`GET /fx/rates` is the third and last of the reads ADR 0035's audit found the client calling with nothing behind it.
It is the narrowest: the recording form asks for it only when the settlement currency differs from the debt currency,
and only to show a hint beside that field. It was deferred out of ADR 0036's slice for one reason, and it was not
size:

**The endpoint's own contract text said "read-only, does not lock anything", and that is exactly the sentence that
made the decision unclear.** The rate provider it would call — the same one the recording path uses — does not merely
fetch a rate: it returns the newest rate at or before an instant *and judges it*, answering `fx.rate_stale` (422) when
that rate has expired and `fx.rate_unavailable` (503) when the pair was never quoted at all. The contract's response
list for this endpoint had `200` and `503`. So building the read meant answering a question the document had left
open: **does the reference read apply the freshness policy that governs locking, or does it serve an old rate and let
the screen disclose the age?**

## Decision

**The reference read applies the same freshness policy as locking. An expired rate is a refusal, not a number.**

The member's question, in the form, is "can I settle this in EUR, and at about what rate?". When the pair's newest
rate has expired, the true answer to the first half is **no** — the recording path will refuse the submission with
`fx.rate_stale`. Serving the expired number would answer the second half and hide the first: the member would pick a
currency, choose a date, write a memo, submit, and be refused — the same refusal, arrived at later, after more work,
with a number on screen that looked usable. The form's own caveat cannot cover that difference either; it says the
rate that counts is fixed at recording time, which is a statement about *timing*, not about *availability*.

Three consequences follow, and they are the implementation's shape:

**1. The two failures travel as themselves, because they are different answers to the member.** `422 fx.rate_stale`
is "this cannot be settled in until the feed catches up" — final, do not retry. `503 fx.rate_unavailable` is "the
server cannot answer yet" — try later. One is a refusal and one is an outage, and a screen that conflated them would
either retry a refusal forever or give up on a pair that gets priced tomorrow. Both codes are the recording path's
own, unwrapped from the provider rather than re-worded, so the client branches on one thing.

**2. The number is never presented as fresher than it is.** The response is the contract's whole `FxRate` schema, so
`quotedAt` and `expiresAt` travel with it, and the form already renders "quoted {quotedAt}" beside the rate.

**3. Staleness is judged in the provider, and only there.** The use case asks for the rate and lets the provider's
failure through; it does not compare `expiresAt` to the clock itself. A second judgement at this layer would be a
second description of one policy (ADR 0021), and the two would disagree the day the policy changes — which is the same
reason the recording path does not judge it either. That is what makes the read and the write fail *identically*,
which is the property the whole decision rests on.

**Also decided, in the same boundary:** a pair of one currency is a `400` rather than an answer, because a fabricated
identity rate is indistinguishable from a quoted one to everything downstream — the rate column, an audit, a
reconciliation. An unknown currency code reuses the recording path's `api.obligation_unknown_currency` (one condition,
one code: the currency is not in the catalogue, so no rate can exist; its message is already about the catalogue and
not about obligations). Malformed parameters use `api.request_incomplete`, the general code three other boundaries

## Consequences

- **ADR 0035's audit has no open reads left.** Four reads the client called and nothing served: the group's members
  (ADR 0035), the obligations list and the currency catalogue (ADR 0036), and this one. The check that would have
  found them all before a person did is still unwritten, and it remains the most valuable thing nobody has built.
- **Six new API tests, taking the Java build from 465 to 471.** Two of them carry this decision: an expired rate is
  refused here *and* refused by the recording path with the same code, and in the happy case the rate served is the
  rate a cross-currency obligation is then locked at. The second is the claim the hint's usefulness rests on.
- **The client needed no code change, and that is evidence rather than luck.** The form already rendered the rate
  query's failure through its problem notice, keyed on the code, and its own comment already said the query's purpose
  was to give "the only useful message the screen can give — that this pair cannot be settled in yet". The client was
  built against the contract; the decision brought the server up to it. One doc comment on the query was corrected to
  name both failures instead of the `503` alone.
- **One type now describes a rate everywhere.** `FxRateResponse` was hoisted out of `FxQuoteDto`, so a locked quote's
  rate and a reference rate are the same type rather than two records with the same nine fields — the same move a
  second screen wanting the client's form field caused (ADR 0035).
- **What this read still does not promise.** It is not a quote: the obligation's own instant decides which rate is
  locked, and a rate can expire between the two requests, so the screen's word "indicative" stays true. What changed
  is that "indicative" now means *this may have moved by the time you submit* rather than *this may not be available
  at all*.

## Alternatives considered - and why rejected

**Serve the expired rate and let the screen disclose its age.** It is the more generous answer to "what would this
cost?", and it was the first option considered — the response already carries `expiresAt`, and a screen could say
"quoted three days ago". Rejected because it makes the screen complicit in a submission the server will refuse: the
member's next action after reading the number is to fill in the form and press record, and the only thing waiting for
them is the refusal the read could have given first. A caveat that says "this may be stale" cannot say "do not bother",
which is the actual answer.

**Add a second port method that reads the latest rate and does not judge it.** Rejected on two grounds: it would put
a second freshness policy in the system (one for locking, one for reading), and the endpoint's "read-only, does not
lock anything" would become the only place that treats a rate the server will not accept as a usable fact. The
sentence was about *locking* — this read does not lock anything, and it still does not, but it answers with a rate the
lock would take or with the refusal the lock would give.

**Answer `400` for an expired rate.** The request is not malformed; it is a well-formed question whose answer is no.
`422` is what the recording path already answers in exactly this situation and what the client already branches on,
and using a different status here would make one condition look like two.

**Leave the contract's response list at `200`/`503` and let the stale refusal be an undocumented `422`.** The failure
is possible, so the document has to say so: a generated client inherits the response list as fact (ADR 0035), and a
code a client can receive but the contract does not name is exactly the drift that audit was about. The list was
extended, along with `400` for the two conditions the boundary refuses.

already answer with, rather than a fourth spelling of "you asked a malformed question".


It is a business question before it is a technical one, because the two answers differ in what a member is told and
when.
