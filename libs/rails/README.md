# libs/rails

**Purpose:** the `PaymentRail` port and its adapters. The seam between reductxn and the outside
world of money movement.

**Public surface:** `io.reductxn.rails.api.PaymentRail` plus its request/result types.

**May depend on:** `libs/kernel`, `libs/ledger` (RULE B4).

**Must NOT depend on:** `libs/netting`, Spring (adapters may use an HTTP client, but the port must not).

**Adapters:**

- `LedgerOnlyRail` - records intent, moves nothing. The default for record-only deployments.
- `MockRail` - deterministic, used by tests and demos.
- `SandboxRail` - CBDC sandbox integration.
- `PspRail` - a licensed PSP.

**Rule:** private keys are never stored. Signing happens on-device or in the PSP wallet.

**Owner:** integrations reviewer.
