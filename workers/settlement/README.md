# workers/settlement

**Purpose:** owns the settlement cycle lifecycle and its tables. Runs the netting solver via `libs/netting`.

**Public surface:** none - it publishes events and writes its own tables.

**Owns tables:** `settlement_cycles`, `settlement_runs`, `settlement_transfers`, `net_positions` (RULE B17).

**May depend on:** `libs/kernel`, `libs/ledger`, `libs/netting`, `libs/rails`, `platform/*`.

**Must NOT contain:** business rules that belong in `libs/*`. Orchestration and persistence only.

**Owner:** backend reviewer.
