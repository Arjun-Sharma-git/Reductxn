# ADR 0009 - Spring Modulith modular monolith

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** RULEs B6, B12-B15

## Context

Microservices would multiply operational surface. A single undifferentiated codebase rots.

## Decision

`apps/api` is one deployable containing explicit internal modules with enforced boundaries,
verified by Spring Modulith plus ArchUnit. Service extraction is deferred until a module's scaling
profile genuinely differs (settlement is the first candidate, and already lives in
`workers/settlement`).

## Consequences

- **Easier:** one deploy, one transaction boundary, simple local development; boundaries are
  verified by tests, so extraction later is mechanical rather than archaeological.
- **Harder:** module discipline must be maintained actively; a shared database means a shared
  failure domain.

## Alternatives considered

- **Microservices from day one** - rejected: distributed transactions for a ledger, and premature
  operational load.
- **Unstructured monolith** - rejected: this is the failure mode the whole document exists to avoid.
