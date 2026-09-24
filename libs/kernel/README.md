# libs/kernel

**Purpose:** shared value types and primitives with no domain policy - `Result`, `DomainError`, id types,
`Clock` abstraction, currency identifiers.

**Public surface:** `io.reductxn.kernel.api.*` - everything else is `internal` (RULE B10).

**May depend on:** nothing (RULE B1).

**Must NOT depend on:** any other reductxn module, Spring, JDBC, HTTP.

**Owner:** platform reviewer.
