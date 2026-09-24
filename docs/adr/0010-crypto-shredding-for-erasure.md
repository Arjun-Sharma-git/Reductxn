# ADR 0010 - Crypto-shredding for erasure

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P3; RULE B17

## Context

Append-only ledgers and erasure rights are in direct tension. Deleting journal entries destroys
audit integrity; refusing erasure is unlawful in several jurisdictions.

## Decision

Personally identifiable data is stored encrypted under a per-subject data key. Erasure destroys the
key, leaving the ledger structurally intact but the personal data unreadable. Ledger rows are never
deleted. Financial aggregates remain valid because they reference surrogate ids, not personal data.

## Consequences

- **Easier:** erasure is honoured without breaking the audit trail or the balance arithmetic.
- **Harder:** key management becomes a hard dependency; a lost key is unrecoverable data loss.
  Retention schedules must be defined per data class, and the KMS becomes a critical path component.

## Alternatives considered

- **Hard delete + tombstone** - rejected: still destroys audit integrity for shared records.
- **Pseudonymise only** - rejected: pseudonymised data remains personal data under GDPR.
