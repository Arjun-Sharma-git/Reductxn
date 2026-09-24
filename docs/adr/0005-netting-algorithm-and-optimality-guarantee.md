# ADR 0005 - Netting algorithm and optimality guarantee

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** RULEs B3, B10; spec `docs/specs/netting.md`

## Context

The minimum-transaction settlement problem is NP-hard in general. A solver alone is therefore not
an acceptable correctness strategy: it can time out, and it can return nothing.

## Decision

Two-tier engine. A **greedy** max-debtor/max-creditor pass is the correctness baseline and always
runs first, guaranteeing at most N-1 transfers for N members with non-zero net position. An
**optimizer** (zero-sum subset partitioning, then MIP via Timefold/OR-Tools) runs afterwards and
is accepted only if it strictly improves on the baseline. Both are pure and side-effect free.

## Consequences

- **Easier:** settlement always produces a valid answer; the optimal result is an upgrade, never a
  dependency; the improvement can be reported as a concrete number.
- **Harder:** two code paths must be kept consistent and tested against each other. Every
  optimizer result is validated against the baseline before acceptance.

## Alternatives considered

- **Greedy only** - rejected: leaves tax savings on the table.
- **Solver only** - rejected: no correctness guarantee under timeout.
