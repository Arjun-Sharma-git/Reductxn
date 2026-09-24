# platform/observability

**Purpose:** OpenTelemetry, Micrometer and Logback configuration shared by every deployable, so
traces, metrics and structured logs are consistent rather than per-module improvisation.

**Public surface:** Spring configuration classes and OTel autoconfiguration only. No API for callers
to program against; deployables get observability by depending on this module and setting
configuration properties.

**May depend on:** `libs/kernel` and infrastructure libraries (Micrometer, OTel, Logback).

**Must NOT depend on:** `apps/*`, `workers/*`, or any other `libs/*`.

**Must NOT contain:** domain concepts. The ledger-specific metric that matters most - an unbalanced
journal entry - is a counter exposed by `libs/ledger`, not defined here. If this module mentions
`Obligation` or `NetPosition`, a boundary has leaked (MASTER-PLAN section 4.1).

**Owner:** platform reviewer.
