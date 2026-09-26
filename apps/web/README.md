# apps/web

React SPA - recording obligations, reading balances, the netting preview, settlement reports. One screen exists
today (the netting preview, ADR 0028); the rest of the plan's route tree arrives screen by screen.

**Public surface:** the app itself; consumed by browsers only.

**May depend on:** `contracts/openapi` (generated client types). Never on Java modules.

**Import direction:** `routes` -> `features` -> `entities` -> `shared` (RULE F1), enforced by
`eslint-plugin-boundaries` rather than by review.

**Must NOT contain:** money arithmetic outside `shared/money` (RULE F9), direct HTTP outside `shared/api`
(RULE F5), a user-facing string outside a catalog (RULE F10), or any pre-1.0 dependency (P9).

**Owner:** frontend reviewer.

## The layers

| Folder | Holds |
|---|---|
| `src/app` | The composition root: the router, the query client, the providers. No business logic and no screens |
| `src/routes` | TanStack Router file-based routes, and **thin** (RULE F4): validate search parameters, wrap the screen in its locale, render one feature |
| `src/features/<name>` | One vertical capability. Public surface is `index.ts`; everything else is private (RULE F8). `obligations` owns the list, the filters' UI and the recording form; `netting` owns the preview |
| `src/entities/<name>` | Domain-shaped client models and their `queryOptions` factories: `currency`, `member`, `obligation`, `netting`, `fx` |
| `src/shared/api` | The generated contract (`schema.d.ts`) and the only `fetch` in the app (RULE F5) |
| `src/shared/money` | Integer minor units, formatted and parsed for a locale. The P1 boundary in the client |
| `src/shared/i18n` | Locale resolution, ICU catalogs, the direction token, the review-state rule |
| `src/shared/ui` | Design tokens and the primitives that encode a rule (`Amount`, `Notice`, the header controls) |
| `src/shared/gsap` | The motion policy: the one place a timeline is created, and the reduced-motion branch |
| `src/shared/lib` | `Result`, refusals. No business knowledge at all (RULE F3) |
| `src/shared/config` | Environment, parsed by Zod |
| `locales` | ICU catalogs per locale. `en` is the source of truth; `_meta.json` holds the review state |

## Commands

| Command | What it does |
|---|---|
| `pnpm dev` | The dev server, proxying `/v1` to `localhost:8080` |
| `pnpm check` | typecheck, lint, catalog check, unit tests, build |
| `pnpm e2e` | Playwright: the browser suite, including the axe contrast check |
| `pnpm codegen` | Regenerate `shared/api/schema.d.ts` from the contract (RULE C1: edit the contract first) |

`pnpm install` needs pnpm 12 (pinned in `package.json`) and Node 22+. The first `pnpm e2e` needs
`pnpm exec playwright install chromium`.

## What is enforced, and where

| Rule | Enforced by |
|---|---|
| F1-F3 (import direction) | `eslint-plugin-boundaries`, as an error |
| F2, F8 (a feature is reached through `index.ts`) | `no-restricted-imports` patterns |
| F4 (thin routes) | Review - no rule can see "one component and no logic" |
| F5 (HTTP only in `shared/api`) | `no-restricted-globals` on `fetch` |
| F9 (no money arithmetic) | `no-restricted-syntax` on dividing by a hundred |
| F10 (no literals) | `no-restricted-syntax` on JSX text |
| F11, F12 (formatting and parsing) | `no-restricted-properties` on `Intl.NumberFormat` and `parseFloat` |
| I1, I2, I3, I7 | `tools/check-catalogs.mjs` |
| I4, I5 (money round trip, digits preserved) | Vitest property tests over four locales |
| The skill's contrast and focus rules | Playwright and axe, in a real browser |
| P9 (GA dependencies only) | `package.json` versions, reviewed |

**There is no CI configuration in this repository**, so these run from `pnpm check` and `pnpm e2e` on a developer's
machine and would be two steps in a pipeline that does not exist yet.

## Two things worth knowing before changing anything here

**Money never becomes a number with a fractional part.** `shared/money` takes its digits from the integer and its
punctuation from `Intl`, and the property tests are what keep that true. If you reach for `toLocaleString` or a
division by a hundred, the lint rules stop you - and they are right to: the version that looks correct is wrong for
large amounts, and a ledger is where that gets noticed late. A *rate* is a scaled integer too (P12), so it is
formatted by the same machinery (`formatRate`) and `rateScaled / 10 ** rateScale` appears nowhere.

**Text is looked up, never written.** A component receives `t` from `useTranslations()`; a key that is missing from
a locale falls back to English per key, and a *key used in a component but absent from `en`* is a build failure.
The one string that is not in a catalog is the product's own name, in `shared/config`.

**A refusal is a value, and a retry is the same attempt.** A refusal from the server is an `ApiProblemError` with a
stable `code`, which the screen renders from `errors.json` and attaches to the field that can fix it
(`features/obligations/model/input.ts`). What a member typed is turned into a request by pure functions, so
over-precise input is refused *in the browser* rather than truncated by a server. And a money write carries an
`Idempotency-Key` that belongs to the **attempt**, not to the click: the form creates it once and every retry -
automatic after a `503`, or the "try again" button - reuses it, which is what makes retrying safe rather than a way
to record something twice. A `422` is never retried, because the answer would be the same.
