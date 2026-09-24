# apps/web

**Purpose:** React SPA - recording obligations, viewing balances, the netting preview, settlement reports.

**Public surface:** the app itself; consumed by browsers only.

**May depend on:** `contracts/openapi` (generated client). Never on Java modules.

**Import direction:** `routes` -> `features` -> `entities` -> `shared` (RULE F1).

**Must NOT contain:** money arithmetic outside `shared/money` (RULE F9), or direct HTTP outside `shared/api` (RULE F5).

**Owner:** frontend reviewer.
