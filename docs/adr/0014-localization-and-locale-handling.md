# ADR 0014 - Localization: ICU catalogs, codes not prose, and locale-safe money

- **Status:** accepted
- **Date:** 2026-09-25
- **Related:** principles P1, P5, P13; ADR 0003, ADR 0013; RULEs F10, F11, F12, B20

## Context

Launch needs several languages: at minimum English, Hindi, Russian and Japanese, matching the
currencies the product supports (`INR`, `RUB`, `JPY`, `USD` regions). Retrofitting localization into
a built UI is close to a rewrite, because every user-visible string must be found, extracted,
namespaced and reviewed. So this is a **Phase 1** decision, not a Phase 5 one.

The hazards are specific, and each has a wrong answer that looks fine in English:

1. **Naive interpolation breaks plurals.** Russian has three plural categories (1, 2-4, 5+), Hindi
   two, Japanese one. `count + " members"` is wrong in Russian and subtly wrong elsewhere.
2. **Money formatting is where i18n collides with P1.** `Intl.NumberFormat` takes a JavaScript
   `number`, so routing an amount through it reintroduces exactly the floating-point path P1 exists
   to forbid. And grouping is genuinely different per locale: `12,34,567.89` for `en-IN` against
   `12 345 678,9` for `ru`.
3. **Input parsing is the mirror problem.** An Indian user types `12,34,567.89`, a Russian user
   `12 345 678,9`, a Japanese user `1234568`. `parseFloat` breaks all three and violates P1.
4. **The API currently returns `title`/`detail` prose.** If the server localizes it must know the
   locale, and the text it returns cannot be cached cleanly or asserted in tests. Worse, adding a
   language becomes a backend release.
5. **Timezone is a correctness issue, not a display one.** A cycle closes at an instant, but "31
   October" is a different instant in Tokyo, Moscow and Mumbai. Without one anchor, two members
   disagree about which cycle an obligation belongs to.
6. **RTL is not needed for these four locales**, but Arabic and Hebrew are plausible. Direction baked
   into CSS and GSAP animations later is expensive to undo.

## Decision

**1. All user-facing text comes from an ICU message catalog.** Files live at
`apps/web/locales/<locale>/<namespace>.json`, ICU MessageFormat, namespaced by feature
(`obligations`, `netting`, `settlement`, `tax`, `errors`, ...). ICU rather than simple interpolation
specifically for plural categories and `select`, because those are the cases that silently
mis-translate.

**2. The API never returns localized prose.** Problem responses carry a stable machine-readable
`code` plus structured `params`; the client localizes from the catalog. `title` and `detail` stay for
developers and logs and are English-only. This is **RULE B20**. The consequence is the point: adding
a language is a frontend change, never a backend release.

**3. Locale resolution order:** authenticated user preference, then the URL, then `Accept-Language`,
then `en`. The resolved locale is part of page identity and must appear in the URL, so a shared link
opens in the language the sharer saw.

**4. Money and numbers are formatted only through `shared/money`, with locale applied to integer
digits.** Never through a raw `Intl.NumberFormat` applied to a `number` amount. Symbols, placement,
grouping and decimal separators come from `Intl.NumberFormat(...).formatToParts`; the digits
themselves come from the exact `long`. This is how P1 survives contact with locale formatting. This
is **RULE F11**.

**5. Amount input is parsed to integer minor units, never through `parseFloat`.** Locale-aware
parsing strips grouping using the locale's separators, then builds minor units by string manipulation
and integer arithmetic. Unparseable or over-precise input is rejected with a code rather than
silently truncated. This is **RULE F12**.

**6. Every group has a settlement timezone.** An IANA zone id on the group, defaulting to the
creator's zone, so the cycle boundary and the effective date of a tax rule are evaluated in one
place. Members see times in their own locale and zone, but the boundary belongs to the group, and
therefore every member agrees which cycle an obligation falls into.

**7. The translation pipeline has an honesty gate.** The `en` catalog is the source of truth. CI
fails on a missing key relative to `en`, an unused key, and any user-facing literal in a component
(**RULE F10**). Machine-translated catalogs must be marked `"reviewed": false`, and the app must
refuse to render unreviewed locales on money-moving and approval screens. This is a financial
product: an MT error in "you will pay" is not a cosmetic defect.

**8. Direction is a token, never a hardcoded assumption.** Launch locales are all left-to-right, but
layout uses CSS logical properties and GSAP animations read direction from a token, so an RTL locale
does not mean rewriting animations, margins and icon mirrors.

**9. Server-side text that we do not control is stored raw and normalized.** Rail and KYC provider
messages are kept verbatim for audit, and additionally mapped to a stable code where possible, so the
UI is not at the mercy of a provider's language settings.

## Consequences

**What becomes easier.**

- Adding a language is a catalog plus a locale entry; no backend change, no schema change.
- Plural and gender correctness is handled by ICU instead of by hand.
- Locale bugs become testable: a catalog check and a formatting property test, rather than a
  manual review in each language.
- The URL carries the locale, so support conversations and shared links are reproducible.

**What becomes harder, and must be accepted.**

- **Every string addition touches a catalog file**, and interpolating a new value into an ICU message
  requires care with escaping (`{`, `}`, and apostrophes in ICU have meaning). Contributors find this
  friction real.
- **`Intl` formatting through `formatToParts` is more code than one `Intl.NumberFormat` call.** That
  cost is accepted deliberately, because the one-line version is a P1 violation.
- **Translation review is a bottleneck.** With four locales and a financial domain, unreviewed machine
  translation must be visibly marked, which means a launch locale can be functionally present but
  flagged, and that state has to be designed rather than discovered.
- **Timezone anchoring must be chosen at group creation and is awkward to change later.** Changing it
  changes historical cycle membership, so it must be effectively immutable, or changes must be
  forward-only from a stated instant.
- **Plural categories differ from what English suggests**, so a Russian reviewer must check the
  `one`/`few`/`many` forms explicitly. The catalog check cannot verify meaning, only presence.

**Alternatives considered, and why rejected.**

- **Server-side localization of API text.** Rejected: makes adding a language a backend release,
  complicates caching, and puts translated prose in logs and error payloads where it cannot be
  asserted or diffed. Codes plus client catalogs is strictly better here.
- **String concatenation with a `count` suffix.** Rejected: broken in Russian and Hindi, which are
  both launch locales. This is the single most common way an "i18n-ready" app turns out not to be.
- **A translation SaaS as the runtime source of truth.** Considered. Rejected for v1 as a runtime
  dependency for text on a money-moving screen: a catalog in the build is reviewable in pull
  requests and cannot change under users. The SaaS can still be the translation *workflow*, with the
  catalog committed.
- **Formatting money with `Intl.NumberFormat` directly.** Rejected: passes the amount as a `number`,
  violating P1, and is exactly the silent-error class P1 exists to prevent.
- **`parseFloat` for input with a locale hint.** Rejected for the same reason, plus it accepts
  garbage like `1.2.3` and truncates precision silently.
- **Per-member settlement timescales instead of one group timezone.** Rejected: two members could
  then disagree about cycle membership for the same instant, which is unreconcilable after the fact.
- **Deferring localization to a later phase.** Rejected on cost: extraction after the UI exists is a
  rewrite of every component, and the plural and parsing decisions have to be made before the first
  screen ships, not after.

