# ADR 0028 - The client starts with the screen that shows what netting saves

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `apps/web` - toolchain (Vite + React 19 + TanStack Router/Query + CSS modules), `shared/`
  (`api`, `money`, `i18n`, `ui`, `gsap`, `lib`, `config`), `entities/{currency,netting}`, `features/netting`,
  `routes/`, `locales/{en,ru}`, `tools/check-catalogs.mjs`, Vitest and Playwright suites
- **Answers:** MASTER-PLAN section 6's claim that modularity is "enforced mechanically" for the frontend, which
  had been true of the Java modules and of nothing else because there was no frontend
- **Closes:** the largest open item on the roadmap - `apps/web` at 0% sources - with its first screen wired to a
  real endpoint
- **Related:** ADR 0007 (TanStack, GA only), ADR 0014 (localization), P1, P9, RULE F1-F12, C1-C3, I1-I7;
  `docs/specs/i18n.md`; MASTER-PLAN sections 6, 7, 8, 13

## Context

Every slice so far has been backend, and the plan said why: `apps/web` was "Phase 1, and 0%", and the modules
without code were the ones whose phase had not arrived. Meanwhile the API grew a real surface - obligations, a
netting preview, runs, decisions - and `apps/api`'s README described three endpoints that no client called.
`docs/specs/i18n.md` had been written in full (locales, ICU, four plural categories, the P1 boundary in money
formatting, the review-state rule) with nothing to run it in, and MASTER-PLAN section 8 listed frontend gates -
import boundaries, locale coverage, error-code parity, locale money safety, "Frontend | Vitest + Playwright" - of
which none existed.

The design intelligence came from the `ui-ux-pro-max` skill, queried for this product ("fintech settlement ledger
dashboard"), which returned a *Financial Dashboard* profile: a corporate navy/grey strategy with high integrity
and conservative accents, a high-contrast dark palette, and Fira Sans with Fira Code. Its priority list - contrast
4.5:1 and visible focus first, then 44px targets and no instant state changes, then no layout shift, then style
consistency, then mobile-first, then a reduced-motion branch - is the order this slice implements in, and the
reduced-motion rule is a test rather than an intention.

## Decision

**1. The first screen is the netting preview, and it is read-only.** `/groups/{groupId}/netting` reads
`GET /groups/{groupId}/netting/preview` and renders three tables: what netting saves per currency, what each
member's position comes to, and who pays whom. It needs no identity, no write path, no optimistic update and no
group list, and it shows the two facts the product exists to produce. Everything the later screens need - the
generated typed client, the currency catalogue, locale-aware money, the feature slice, the design tokens - is
exercised by it, so the next screen is a screen rather than an infrastructure project.

**2. The contract generates the types; the client is ours.** `openapi-typescript` generates
`shared/api/schema.d.ts` from `contracts/openapi/reductxn-api.yaml` (committed, RULE C2). MASTER-PLAN section 7
prescribes `openapi-fetch` for the other half, and **it has never reached 1.0, so P9 forbids it**. What replaced it
is forty lines: `request<T>()` over `fetch`, typed by `paths`, turning a response into a value or an
`ApiProblemError` carrying the RFC 9457 `code` and `params`. The *schema* is generated - hand-writing it would be a
second copy of the contract - and the *transport* is small enough for this repository to own. `fetch` is banned
everywhere else by ESLint (RULE F5).

**3. Money formatting is the P1 boundary in the client, and it is a substitution.** `shared/money` takes the digits
from the integer by slicing its string, and the pattern - symbol placement, decimal separator, group separator and
group *sizes* - from `Intl.NumberFormat.formatToParts`, probed with `0` and with a `BigInt`. No value is divided,
rounded or parsed as a float: `Number.parseFloat` is banned by ESLint, `Intl.NumberFormat` is banned outside this
folder, and dividing by a hundred is banned everywhere. The two properties the spec says matter most are property
tests over four locales (`en-IN`, `en-US`, `ru`, `ja`): I4, that formatting and parsing round-trip, and I5, that
the digits of the output are exactly the digits of the integer.

**4. `shared/i18n` is the only source of text.** Catalogues live in `apps/web/locales/<locale>/<namespace>.json`,
and the layer flattens them to `namespace.key`, formats through ICU (`intl-messageformat`), resolves the locale
from the URL and then the browser, writes `lang` and `dir` on the document, and enforces the review-state rule: an
unreviewed locale is refused on a screen that moves money, English is shown, and a notice says why. A user-facing
string in a component is a lint error (RULE F10), with the product's own name kept in `shared/config` because a
brand is configuration rather than copy.

**5. The design tokens are the skill's, and the departures are deliberate.** The palette, the corporate strategy
and the type pairing come from the query. Two things changed: **not dark-only** (the query's own style recommends
against a light mode; a ledger is read in daylight, both themes come from the same navy/grey strategy, and
`prefers-color-scheme` decides by default) and **Fira Code only for amounts** (a code font for headings reads as a
terminal, while tabular figures on money are the whole reason to have a monospaced face at all). Every colour pair
used for text carries its *measured* WCAG ratio in `tokens.css`, and axe re-measures them in a browser: a token
edited to something prettier fails a test rather than shipping unreadable text.

**6. Styling is CSS modules over tokens, with no framework.** The plan prescribes a design system (`shared/ui`),
not a utility framework, and names none. Tokens in one file, one module per component, logical properties
throughout (`margin-inline-start`) so the reserved RTL locales are a direction token rather than a rewrite.

**7. Server state through Query, and almost no client state.** TanStack Query owns everything the server owns,
with an explicit retry policy: never on 4xx, because a refusal is an answer rather than a reason to ask again, and
twice on a network failure or a 503. There is no store yet - Zustand arrives with the first UI state that needs
one - and no optimistic update until there is a write path.

**8. The gates the plan promised are now real, and one of them is new.** `eslint-plugin-boundaries` enforces
F1-F3 (the arrow `routes -> features -> entities -> shared`), `no-private` and the `no-restricted-imports` patterns
enforce F2 and F8, `no-restricted-globals` enforces F5, and F9/F10/F11/F12 are the money and text rules. Nothing
in this repository runs on a server yet - **there is no CI configuration at all** (a finding, recorded below), so
the gates run from `pnpm check` and `pnpm e2e`, and would be two steps in a pipeline that does not exist.
`tools/check-catalogs.mjs` is the fourth: I1 (every key, or the locale is flagged incomplete), I2 (no unused key),
I3 (error-code parity **in both directions**, with the server's codes collected from the Java sources rather than
listed by hand) and I7 (a plural message carries every category its locale requires, read from `Intl.PluralRules`
rather than from a table).

## Consequences

- **The API has a client, and the contract has a second consumer.** The generated types make a route the contract
  does not define a compile error, and the catalogue check makes an error code without wording a build failure -
  which is the I3 gate the plan had listed as "not built" since it was written.
- **Three defects in the client were found by its own tests, and each is recorded where it happened.**
  `formatToParts(0)` has no group separator in it, so reading the grouping pattern from the *value* probe produced
  ungrouped amounts in every locale; the secondary group size is the *last but one* chunk, so `en-IN` grouped as
  `1,2,3,4,567`; and mapping digits by looking them up in a table turned the grouping space into a zero, because
  `Number(' ')` is `0`. None of the three would have been visible in a single example test, which is the argument
  for the property loop.
- **A fallback message is formatted with the source language's rules.** An English sentence rendered with
  Japanese plural rules said "1 currencies" - Japanese has one category, so the English `one` form was never
  selected. The fallback is now English all the way down, which is the difference between a partial locale reading
  as *missing translation* and reading as *broken grammar*.
- **The innermost locale provider owns the document's `lang`.** The shell provides the URL's locale and a money
  screen provides its own with `requireReviewed`; both wrote `lang`, and React runs a child's effects before its
  parent's, so the outer one won - English text with `lang="ru"`, which a screen reader would announce wrongly.
  The browser test that asserts the notice and the attribute together caught it.
- **A hash-anchored skip link and a first-tab assertion.** The focus test initially failed with `body` as the
  active element: it pressed Tab before React had rendered. The fix is a wait for the heading, and the assertion
  is now that the *skip link* takes focus and has a visible outline - the skill's first priority, made mechanical.
- **The browser suite stubs the API, and that is a cost with two payments.** The fixtures are typed by the
  generated schema, so a field the contract does not have fails the type check, and every endpoint's server half
  has container tests against Postgres. What the browser suite is for is the client's behaviour *given* a response.
- **Pinned versions, and one pinned downwards.** `react` 19.3, `@tanstack/react-router` 1.170,
  `@tanstack/react-query` 5.103, `zod` 4.6, `intl-messageformat` 12.1, `gsap` 3.15, Vite 8.3, Vitest 5.0, ESLint
  10.11, Playwright 1.63 - all GA (P9). **TypeScript is pinned to 5.9 and not 7.0**, which is what the registry
  resolves: `openapi-typescript` uses the compiler API, and TypeScript 7's does not have `ts.factory` where it
  looks. That is a compatibility constraint rather than a preference, and it is the same kind of constraint
  MASTER-PLAN's toolchain section records for Java.
- **Two lint mechanisms disagree about one rule.** `boundaries/no-private` is deprecated in favour of
  `boundaries/dependencies`, and the plugin says so on every run. Migrating is a real piece of work - the rule is
  replaced by a selector-based configuration - and doing it blind would risk weakening F2 while looking like a
  tidy-up. Recorded, not done.

## Alternatives considered - and why rejected

**`openapi-fetch`, as MASTER-PLAN section 7 prescribes.** It is the natural partner of the generated types and it
would have saved writing a client. It is also pre-1.0, and P9 says the frontend takes GA releases only ("we are not
a QA lab for our own dependency tree"). The interesting part is what the rejection *forced*: a forty-line client
whose problem handling is ours, tested by the browser suite, instead of a dependency whose behaviour on a
non-conforming response would be somebody else's decision.

**Hand-written client types, or Zod schemas parsed from responses.** Both are tempting and both put a second copy
of the contract in the client, which is what RULE C1 and C3 exist to prevent. The generated `schema.d.ts` is
committed, so a clone type-checks without running codegen, and regenerating is a step whose diff CI would check.

**Tailwind or a component library.** Neither is prescribed anywhere in the plan, and both would import a design
system rather than build the one `shared/ui` is for. The skill's contribution is tokens and rules, not a utility
vocabulary; CSS modules keep a component's styling next to it and its values in one file.

**Bright/dark as a boolean, or dark-only.** The query's palette is dark-first and its style recommends against a
light mode. A boolean cannot express "follow the system", which is what most users want and what the button could
then never get back to; and a ledger read in daylight with no light theme is a product decision made by a style
database.

**Fira Code for headings, as the query pairs it.** It reads as a terminal in a settlement report. Where a
monospaced face earns its place is on numbers - tabular figures align a column of money - so it is the amount face.

**A property-testing library for the money suite.** TypeScript has no `jqwik`, and every candidate is another
dependency, another generator vocabulary and another seed file to keep. A deterministic generator over a fixed set
of seeds plus a few hundred arithmetic values runs the same cases on every machine and needs no shrinking: the
failures it found were reported with the locale, the amount, the formatted string and the extracted digits, which
was enough to fix each one on sight.

**Putting the locale in the path (`/ru/groups/...`).** The spec leaves this open (section 11). A query parameter
keeps the route tree about the domain - `/groups/{groupId}/netting` reads the same in every language - and keeps
the locale out of every link a screen builds. The user-preference step of the spec's resolution order is still
unimplemented, because it needs identity.
