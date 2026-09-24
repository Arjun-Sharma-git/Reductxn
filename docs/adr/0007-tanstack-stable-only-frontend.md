# ADR 0007 - TanStack, stable releases only, in the frontend

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P9; RULEs F1-F9

## Context

TanStack Router was preferred over React Router for familiarity and for type-safe search params,
which suit a ledger whose views are heavily filtered. TanStack also ships packages at very
different maturity levels: Router, Query, Form, Table and Virtual are GA, while DB (0.4.1),
Store (0.11.1), Pacer (0.22.0) and Start (RC) are not.

## Decision

Adopt TanStack Router, Query, Form, Table, Virtual and the GA devtools. **Do not** adopt TanStack
DB, Store, Pacer, Form Devtools, or Start. Use Zustand 5 for the small amount of client state.
Router runs in SPA mode against the Spring Boot backend.

## Consequences

- **Easier:** no pre-1.0 dependency owns authoritative financial state or the data layer;
  Router's `validateSearch` with a Zod adapter gives shareable, bookmarkable filtered views;
  `ensureQueryData` in loaders removes loading-state jank before animations start.
- **Harder:** we forgo TanStack DB's optimistic-write-with-rollback model and implement optimistic
  updates manually with TanStack Query `onMutate`. We also forgo Start's SSR - acceptable, since
  authentication is entirely behind login and SEO is not a product goal.

## Alternatives considered

- **Adopt DB and Store now** - rejected: v0 semantics in the layer that decides what a balance is.
- **React Router** - rejected: weaker search-param typing; team familiarity.

## Revisit when

TanStack DB reaches 1.0. It may then replace hand-rolled optimistic updates for the read-side view
layer only, behind a boundary that keeps removal cheap.
