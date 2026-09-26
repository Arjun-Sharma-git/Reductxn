# Architecture Decision Records

One file per decision. Template in `docs/MASTER-PLAN.md` section 12.

Any change to a **RULE**, the module graph, the stack, or a principle P1-P13 requires an ADR.

| ADR | Title | Status |
|---|---|---|
| 0001 | Non-custodial architecture | accepted |
| 0002 | Java + Spring Boot as the single backend | accepted |
| 0003 | OpenAPI as the contract source of truth | accepted |
| 0004 | Append-only double-entry ledger | accepted |
| 0005 | Netting algorithm and optimality guarantee | accepted |
| 0006 | Tax law as configuration | accepted |
| 0007 | TanStack, stable releases only, in the frontend | accepted |
| 0008 | Group as the shard key | accepted |
| 0009 | Spring Modulith modular monolith | accepted |
| 0010 | Crypto-shredding for erasure | accepted |
| 0011 | Netting must not increase the settlement tax | accepted |
| 0012 | Multicurrency settlement: net per currency, convert the residual only | accepted |
| 0013 | Exact FX rates, cross-currency entries, and the rounding residual | accepted |
| 0014 | Localization: ICU catalogs, codes not prose, and locale-safe money | accepted |
| 0015 | `planning` computes the plan, `settlement` executes the run | accepted |
| 0016 | Quorum is over a run's participants, until membership exists | accepted |
| 0017 | A settled run books its movements, and settling is terminal | accepted |
| 0018 | The worker drives what it owns, and an operator triggers the rest | accepted |
| 0019 | A rail's refusal says whether asking again could help | accepted |
| 0020 | The API records a member's answer; the run judges it | accepted |
