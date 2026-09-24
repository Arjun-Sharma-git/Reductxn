# libs

**Purpose:** the pure domain core. This is the auditable heart of reductxn.

**RULE B5:** nothing here may depend on `apps/*`, `workers/*`, `platform/*`, or Spring.

**Why it matters (P4):** an auditor must be able to run the netting engine standalone and
reproduce a settlement without a database or a web server.

**Module graph:**

```
kernel  <- nothing
ledger  <- kernel
netting <- kernel, ledger
rails   <- kernel, ledger
```

**Owner:** ledger reviewer for `ledger` and `netting` (two-reviewer bar); platform reviewer for `kernel`; integrations reviewer for `rails`.
