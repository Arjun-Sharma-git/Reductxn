# ADR 0029 - A refusal is a value, and a retry is the same attempt

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/web/src/entities/{member,obligation,fx}`, `apps/web/src/features/obligations`,
  `apps/web/src/routes/groups/$groupId/obligations.tsx`, the group navigation, `apps/web/src/shared/money`
  (`formatRate`), `locales/en/obligations.json`, `tools/check-catalogs.mjs` (the `form.` client namespace)
- **Closes:** the client's first *write* path - `POST /obligations` with its idempotency key, its two distinct
  failure modes and its optional FX quote - and the plan's `obligations` route, which was specified as a filtered
  list with nothing behind it
- **Related:** ADR 0012 (per-currency partitions, locked quotes), ADR 0014 (text is looked up), ADR 0027 (rates
  arrive from a provider), ADR 0028 (the client exists); P1, P8, P9, P12; RULE F4, F5, F9-F12, C1; L2

## Context

ADR 0028 gave the client one read-only screen. It also left two things visibly unfinished: `shared/money`'s
`parseAmount` had **no caller** - RULE F12 was implemented and unused - and `newIdempotencyKey` was exported from
`shared/api` with nothing asking for it, so P8's "every state-changing operation is idempotent" had no client half.

Meanwhile `POST /obligations` was the richest endpoint in the contract and nothing could reach it. It is not a
simple insert: it takes an optional `settleInCurrency` and locks an FX quote when it differs; it refuses a stale
rate with `422 fx.rate_stale` and answers `503 fx.rate_unavailable` when the pair has no rate at all, with the
contract stating the difference in as many words ("a client that treated the two alike would either give up on a
request that would succeed later or retry one that never will"); and it requires a memo, because a commitment
without a stated reason is one nobody can adjudicate later.

## Decision

**1. The list and the form are one screen, and the filters are the URL.** The plan's route tree has
`obligations.tsx # ?status&member&from&to&sort (Zod)`, and that is what this is: the list is filtered by search
parameters so a narrowed view is a link somebody can send, and the route parses them (RULE F4) while the feature
receives them as data and reports a change. The *recording form* sits on the same screen rather than on a
`/obligations/new` route, because a member records a commitment in order to see it appear: splitting the two would
put a navigation between an action and its result, and the optimistic row would land in a list nobody was looking
at.

**2. What a member typed becomes a request through pure functions.** `features/obligations/model/input.ts` does the
work - no React, no DOM - and the component is then layout and state. Two rules are enforced locally *with the
server's own codes* (`api.obligation_same_party` for the same member on both sides, `api.obligation_request_incomplete`
for a missing party or reason): the member reads one sentence for one rule whether it was caught early or answered
late, and the catalog does not grow a second entry for it. It is a check before the request, not a copy of the
server's validation - anything else arrives as a problem and is mapped to the field that can fix it, which is the
skill's "the error belongs beside the field" applied to a server that answers with codes rather than prose (B20).

**3. The amount is parsed, never floated.** `parseAmount` gets its first caller: the typed text goes to integer
minor units for the chosen currency's exponent, and over-precise input is **refused rather than truncated** (RULE
F12, P1). The source of that exponent is `/currencies`, not a constant - yen have no minor unit and rupees have two,
and a client that assumed two would render a yen amount as one hundredth of itself. The browser test asserts the
refusal *and* that no request was sent, because "refused locally" is only worth having if it is local.

**4. A rate is a scaled integer, so the client formats it the way it formats money.** P12 says an FX rate is a
scaled integer exactly as an amount is, and the Java side enforces that with `forbidFloatingPointMoney`. The
client's counterpart is `formatRate`, which shares the money formatter's machinery: the digits come from the integer
and the punctuation from `Intl`, and `rateScaled / 10 ** rateScale` appears nowhere. What the form shows is the
**indicative** reference rate for a cross-currency choice, labelled as such, because the quote that counts is the
one the server locks at the obligation's instant - the alternative would be the screen promising a number it does
not control. It exists at all because ADR 0012 asks the product to state the cost of the currency choice rather
than only the saving from netting.

**5. The idempotency key belongs to the attempt.** It is created when the form first renders and replaced only
after a successful record, so every retry of an attempt carries the same key. That is what makes the retry policy
safe *and* generous: the client retries once by itself on a `503` or a network failure, and the button offers "try
again" for a member who would rather not wait - and if the first attempt actually succeeded with its response lost
on the way back, the retry collides with the recorded obligation instead of creating a second one (P8). A `422` is
retried **never**, because the answer would be the same; the form says so rather than leaving a member pressing a
button that cannot work.

**6. The new commitment appears before the server has answered.** `onMutate` inserts a provisional row - marked
`pending`, identified by the key - into the view the member is looking at, and `onError` removes it. The rollback is
not decoration: a refused cross-currency commitment that stayed in the list would be a commitment the screen
invented. Which view the row lands in is decided by *deriving the cache key from the same `obligationsQuery` the
screen used*, rather than by naming a view in the mutation.

**7. The route declares that it is a money screen.** `staticData.requireReviewedLocale`, which ADR 0028 introduced
for the preview, applies here too - recording a commitment is a money-moving act, so an unreviewed translation does
not get to write the form either.

## Consequences

- **Five defects, and the most expensive one had been hiding behind a graceful degradation.** The catalog loader
  walked Vite's JSON *module namespace* rather than the JSON document, and a named export cannot exist for a key
  containing a dot - so `common.localeName.en` rendered as a raw key, and **every server error message in
  `errors.json` had been missing since ADR 0028**. `ProblemNotice` degraded that into "this screen has no wording
  for the reason yet" plus the code, so it looked like a small gap in the catalog rather than a broken loader. The
  glob also matched only the namespace files and never `locales/_meta.json`, so the review state was empty and
  every locale was treated as unreviewed - which made the previous slice's review test pass **for the wrong
  reason**, since its subject `ru` is unreviewed either way. Both are now asserted (a *dotted* key, and `en` being
  reviewed), because the catalog check reads the JSON files directly and could never have seen either.
- **An ICU message with a missing parameter threw inside a render and blanked the screen.** `ledger.currency_mismatch`
  is documented as carrying `{expected}` and `{actual}`; a response without them - my own test stub, in this case -
  made `IntlMessageFormat.format` throw, React unmounted the tree, and a member would have seen nothing at all.
  `translate` now catches a formatting failure and returns the message with its placeholders visible: worse than a
  fully formatted sentence, much better than an empty page, and self-diagnosing - the placeholders name what is
  missing.
- **The optimistic row went into a cache entry nobody was reading.** The first version named the view ("the open,
  unfiltered view") and inserted into that query key, while an unfiltered screen keys its query with `null` filters
  - so the provisional row was written where no component looked. The browser test that asserts the pending row
  exists found it, and the fix is the principle this codebase applies to rules: do not keep a second copy of a
  decision. The mutation now takes the filters the screen is showing and derives the key from the same
  `obligationsQuery`.
- **A stub that answers `201` and returns an unchanged list is a server that loses the write.** With a stateless
  stub, the client's post-success invalidation *erased* the row the test had just watched arrive - which looked
  like a client bug and was not. The stub remembers what it accepted, which is what made it possible to tell the
  two apart.
- **The retry policy is per mutation, not global.** `app/providers.tsx` sets `mutations: { retry: false }`, because
  a mutation that failed for a reason a retry cannot fix should not be repeated; this one overrides that with its
  own predicate. The browser test asserts the property rather than the mechanism: **every request in a 503/retry
  cycle carries one distinct idempotency key.**
- **The first form is hand-rolled, and the plan's stack says TanStack Form.** It does not use it yet. The reason is
  that this form's rules are *pure functions with tests* - a typed amount becoming integer minor units, a server
  code becoming a field - and what a form library would add here is state plumbing around rules it neither
  expresses nor verifies. It arrives when a form has enough fields for its own value to outweigh that, and the
  seam is small: the component consumes `localRefusals`, `refusalsOf` and `amountOf`, which do not care who calls
  them.

## Alternatives considered - and why rejected

**A separate `/obligations/new` route.** Cheaper to build and better on a phone, and it separates a long form from
a long table. Rejected because the optimistic row then lands in a list the member is not looking at, and because
the plan already places the filters on this route, so the two would share a cache key and a filter model across a
navigation. Recorded as a real option for a mobile layout rather than as a mistake.

**Showing the converted amount - what the creditor will receive - instead of the rate.** Tempting, and it is the
number a member actually cares about. Rejected because computing it means *multiplying* by a scaled rate and
rounding the result, and the rounding rule belongs to `libs/fx`: the server applies it when it locks the quote. A
client that showed its own rounded figure would be a second implementation of P12's arithmetic, and the two would
eventually disagree by a paisa on a screen that promises a concrete amount. The form shows the rate, and says the
locked quote is what counts.

**Retrying a `422` as well, since the retry is idempotent anyway.** Idempotent is not the same as useful: a `422`
is the server's answer, and repeating a question it has already answered is load and a slower failure. The
distinction is the contract's, and it is now one function (`worthRetrying`) with a test either way.

**A Zod schema for the whole form, parsed on submit.** The amount cannot be expressed as a Zod number - it is
integer minor units for a currency whose exponent arrives over the network - and the two local rules already carry
the server's codes. Zod validates the *search parameters* on the route, which is what the plan asks of it.
