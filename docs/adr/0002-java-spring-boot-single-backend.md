# ADR 0002 - Java + Spring Boot as the single backend

- **Status:** accepted
- **Date:** 2026-09-24
- **Related:** principle P7

## Context

The original proposal was Express + TypeScript end to end. reductxn's most CPU-intensive work is
the netting solver (subset-sum / MIP), and its most batch-intensive work is cycle settlement.

## Decision

One backend language: Spring Boot 4.1.x on Java 25 LTS, Gradle 9 multi-project. No polyglot.

## Consequences

- **Easier:** true CPU parallelism and mature solvers (Timefold, OR-Tools, ojAlgo) enable a
  provably optimal netting result; Spring Batch gives restartable, partitioned settlement;
  virtual threads make blocking JDBC cheap; JVM is the native language of payments and audit.
- **Harder:** no shared types with the frontend - mitigated by ADR 0003. Higher memory per pod
  and slower cold start than Node.

## Alternatives considered

- **All-TypeScript** - rejected: weaker optimizer, no true batch framework, float-money footguns.
- **Polyglot (Node API + Java worker)** - rejected: two build systems, pipelines, and on-call
  surfaces for a small team.

## Note

Raw scalability was **not** the deciding factor. At 10k writes/s the ceiling is the database and
connection pooling, which are identical in either runtime.
