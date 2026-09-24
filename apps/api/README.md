# apps/api

**Purpose:** HTTP boundary, identity, groups, obligations, settlement orchestration. Modular monolith.

**Public surface:** `io.reductxn.api.web.v1.*` (HTTP only, via generated DTOs).

**May depend on:** all `libs/*`, all `platform/*`.

**Must NOT depend on:** `workers/settlement` (RULE B6).

**Package layout:** `web` -> `application` -> `port` -> `adapter` (RULE B12-B16).

**Owner:** backend reviewer.
