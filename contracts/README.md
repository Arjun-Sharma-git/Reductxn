# contracts

**Purpose:** the interface between reductxn and every client - HTTP and events.

**Public surface:** `openapi/reductxn-api.yaml` (source of truth), `events/*.json` (versioned Kafka schemas).

**May depend on:** nothing.

**Must NOT contain:** business logic, or hand-written types that duplicate a schema.

**Owner:** API reviewer. Generated artefacts carry an AUTO-GENERATED header (RULE C2).
