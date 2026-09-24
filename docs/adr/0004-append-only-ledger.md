# ADR 0004 - Append-only double-entry ledger

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P3; RULEs B16, B17

## Context

A ledger that can be edited cannot be audited. Balance columns that are mutated in place lose the
history needed to answer "why is this number what it is".

## Decision

`journal_entries` and `postings` are append-only and enforce double-entry balance per currency.
Corrections are compensating entries, never updates. Balances are **projections**, never the
source of truth. Each entry carries `prev_hash`/`hash`, forming a tamper-evident chain. The
application's database role has **no UPDATE or DELETE grant** on these tables.

## Consequences

- **Easier:** full audit trail; reproducible balances; tamper evidence; simple concurrency
  (insert-only hot path means no row-lock contention).
- **Harder:** storage grows monotonically (mitigated by partitioning and archival); fixing a
  mistaken entry means writing a reversing entry, which must be explained in the UI.

## Alternatives considered

- **Mutable balances** - rejected: unauditable and loses history.
- **Event sourcing only, no relational ledger** - rejected: harder to query and to reason about
  invariants for regulators.
