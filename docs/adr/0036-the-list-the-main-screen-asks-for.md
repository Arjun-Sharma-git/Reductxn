# ADR 0036 - The list the main screen has always asked for

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `GET /v1/obligations` (filters, sort, clamp) in `apps/api`, the status derivation shared by the list
  and the replay path (`ObligationStatuses`), and `GET /v1/currencies`; the client's `sort` parameter narrowed to the
  two orders the API accepts
- **Related:** ADR 0035 (the audit that found these reads), ADR 0034 (the same shape of hole, one screen smaller),
  ADR 0021 (one description of a thing), ADR 0013, ADR 0032 (the worker whose runs the status is derived from);
  RULE B12, B16, B17, B20, B21; P1, P8

## Context

ADR 0035 audited the contract against the controllers and found four reads the client calls that nothing served. It
fixed one (the group's members) and recorded three: `GET /v1/obligations`, `GET /currencies` and `GET /fx/rates`.

**Two of them are this slice, and the first is the worst of the four.** The obligations list is the screen the whole
interface is organised around: it has had its filters in the URL since it was built — status, member, dates, sort —
its form posts to an endpoint that works, and until now the only way to see an obligation this API had recorded was
to record one and read the response. `GET /currencies` is quieter and reaches further: the contract says a client
needs the exponent to render a `Money` at all, and the server deliberately does not repeat it per amount, so with
that read missing **money rendered nowhere** — not one screen falling back to a uuid, but every amount on every
screen waiting for a catalogue that never came.

Two things had to be decided along the way, and both had been wrong in the code for a while:


## Decision

**1. The read exists, with the contract's own narrowings and a page instead of a cursor.** `status`, `memberId`,
`from`, `to` and `sort` all narrow, as the contract describes them, and `limit` (default 50, maximum 200, clamped) is
new. `cursor` is removed from the contract rather than left unimplemented, which is ADR 0035's own lesson applied to
the document it was learned from: a parameter nothing serves is a promise a generated client inherits as a fact.
Pagination is genuinely owed and is recorded in the plan; this endpoint answers a page, and a page is what a screen
renders.

**2. The status is derived, in one Java expression, rendered twice.** `ObligationStatuses.of(...)` is a single CASE
over this module's `voided_at` and the settlement worker's runs, and `JooqObligations` uses that one expression as the
column the response carries *and* as the condition the filter compares. Rendered twice is not the same as written
twice: there is one place where the four words are decided, so a page cannot contain a row whose status it would not
display — which a `WHERE` holding its own copy of the CASE could.

**3. A run that ended without settling releases its commitments.** The worker's states split three ways, not two:
`done` settled; `rejected` and `failed` released, so the commitment is `open` again; the five while-it-is-happening
states hold, so it is `netted`. The obvious rule — "a link means netted unless the run is done" — would call a
commitment netted forever after a failed run, and a commitment nothing is settling is owed. This is also the reading
`JooqOpenObligations` already used to decide what a preview may offer, and the two must agree or a group previews one
thing and reads another.

**4. The replay path reads the same derivation.** `ObligationStore.findExisting` reported `open` for anything not
voided, so a retried request could tell a client a settled commitment was still owed while the list said otherwise.
Two answers to one question is the failure ADR 0021 names, so the store now derives the status the same way — which
cost it a local table alias, because the derivation's correlated subqueries need qualified columns (the trap
`JooqCycles` recorded). A test asserts the two agree.

**5. The catalogue serves a code and an exponent, and nothing else.** `is_cbdc` and `jurisdiction` are columns with no
consumer: a rail needs the first, tax the second, neither exists. The read serves what a client needs to render money
and the omission is pinned by a test, because an omission is a decision and a decision nothing asserts reverses
quietly.

**6. `/fx/rates` is deliberately not in this slice, and the reason is a decision it needs first.** The read would go

## Consequences

- **A screen that posted but could not read now reads.** The obligations list answers with the same shape the form's
  `201` does — `RecordedObligation` through the same mapper — so a row and a created obligation cannot describe
  different things. Thirteen new API tests (ten for the list, three for the catalogue), taking the Java build from
  452 to 465.
- **Three of the four states became producible, and the fourth's meaning got sharper.** `netted` and `settled` were
  in the enum and unreachable; `open` now means "owed and nothing is settling it", which is what a run that gave up
  leaves behind. The enum's own comment said the other two were impossible until the worker existed — the worker
  existed, and the comment was the only thing keeping the code consistent with it.
- **Two mistakes were made and are worth keeping.** The first design derived the status in a subquery so the filter
  could compare against it — and PostgreSQL does not resolve a select alias in a `WHERE`, so the query was a syntax
  error; the second tried a derived table, which makes jOOQ name every column `"page"."incurred_at"` and quietens
  nothing, because the derivation still has to appear inside it. What works is one Java expression rendered twice:
  once as the response's column and once, unaliased, as the filter's condition. Both attempts were caught by running
  the SQL rather than by reading it, which is the only way a query of this shape gets checked.
- **An architecture rule caught a bug the compiler would not have.** Writing `toUpperCase(Locale.ROOT)` in the
  boundary was rejected by `b20_api_has_no_way_to_localise` — no `java.util.Locale` in `io.reductxn.api..`, because
  "a server that cannot see a locale cannot render one". The rule was right twice over: case conversion with an
  implicit locale is the Turkish-`i` bug, and the vocabulary belonged on the enum all along, next to the contract's
  four words and beside the exhaustive switch that makes a new constant fail to compile until somebody names it.
- **What is still open.** `GET /fx/rates` (ADR 0035's third read, deferred above with the decision it needs);
  pagination cursors for every list (recorded in the plan, and this slice removed one promise rather than adding a
  half-implementation); balances, tax, invites, groups, activity and identity, which the plan already tracks.

## Alternatives considered - and why rejected

**A derived table, so the status could be filtered by name.** It reads well and it is what a first attempt did — and
jOOQ's own metadata names every column after the table (`"page"."id"`), which forced eleven more constants and made
the "read by name" mapping impossible; meanwhile the derivation still had to be inside the select. The alias
problem it was meant to solve is solved by rendering the same Java expression twice.

**Filtering with a second copy of the CASE.** Writing the four branches once for the column and once for the
`WHERE` would look identical and be wrong: a row could be selected by a filter for `open` and displayed as `netted`
if the two copies ever drifted, and nothing would catch it. The failing test that pins this asserts that they agree.

**Storing the status as a column.** It would be faster and it would be a second source of truth: `netted` and
`settled` are facts about a *run* that changes as the run progresses, so the column would need updating by whoever
touches a run — the worker, which does not own this module's table (RULE B17), and a last-writer-wins field that
nobody re-derives.

**Keeping `cursor` in the contract.** It was there, and removing it is the same decision ADR 0035 recorded: a
parameter nothing serves is a promise every generated client inherits as a fact. A cursor arrives with the index and
the ordering that make it meaningful; until then the document says `limit` and the code clamps it.

**Closing the `Sort` vocabulary by accepting any string.** A sort this build does not order by would be answered
with the default order — a different question from the one asked, answered silently. It is refused, and the client's
route narrows `sort` to the same two words so a typo in a bookmark becomes the unfiltered view instead.

through `FxRateProvider`, which judges staleness and throws `fx.rate_stale` — a 422 the contract's `/fx/rates`
response list does not have, because the endpoint was written as "read-only, does not lock anything". So building it
means either adding that failure to the contract (a reference rate can be stale, and the screen should say so) or
deciding that a reference rate ignores the max-age policy that governs locking. That is a question for the slice that
builds it, not a line to add quietly. What the endpoint affects is narrower than the other two: the cross-currency
form's *indicative* hint, which is why the same-currency path works without it.

- **An obligation's status was a two-valued guess.** `ObligationStatus` declares the contract's four words and says
  in its own comment that `NETTED` and `SETTLED` are "not producible until the worker exists" — a sentence that
  stopped being true when the worker was built (ADR 0032). The read path derived the status from `voided_at` alone,
  so an obligation a settled run had already discharged was reported `open`.
- **The contract promised a `cursor` and no `limit`,** which is a promise to answer with every obligation a group has
  ever recorded.
