# ADR 0003 - OpenAPI as the contract source of truth

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P5; RULEs C1-C4

## Context

With Java backend and TypeScript frontend there is no shared type system. Hand-written DTOs on
both sides drift silently, and the drift surfaces as runtime bugs in money-handling code.

## Decision

`contracts/openapi/reductxn-api.yaml` is edited first and is authoritative. A TypeScript client
and Java interfaces are generated from it. CI fails if regeneration produces a diff.

## Consequences

- **Easier:** one place to reason about the API; generated clients; a real contract for auditors,
  PSP partners, and the future mobile app.
- **Harder:** codegen step in the build; some generated types need mapper layers to domain types.
  Generated files are committed, so diffs must be reviewed for intent, not noise.

## Alternatives considered

- **tRPC** - rejected: poor fit for third-party consumers, webhooks, and public API surface.
- **Hand-written DTOs both sides** - rejected: guaranteed drift.
