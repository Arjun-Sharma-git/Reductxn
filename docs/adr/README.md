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
| 0021 | A read model projects a run's rows; it does not recompute its judgements | accepted |
| 0022 | A refusal is an answer with a consequence | accepted |
| 0023 | The worker writes its verdicts down for readers that cannot compute them | accepted |
| 0024 | A silence is not evidence, so escalation is a report | accepted |
| 0025 | A person's word is evidence, so a resolution is attributed | accepted |
| 0026 | An answer of "still pending" is not progress, so a stale submission is reported too | accepted |
| 0027 | A rate arrives on a schedule, from a provider the deployment names | accepted |
| 0028 | The client starts with the screen that shows what netting saves | accepted |
| 0029 | A refusal is a value, and a retry is the same attempt | accepted |
| 0030 | A run has to be findable before it can be answered | accepted |
| 0031 | The gates run somewhere that is not the machine that wrote the change | accepted |
| 0032 | A cycle and a run are two acts, and the second one happens without a person | accepted |
| 0033 | The API opens the cycle; the worker still computes it | accepted |
| 0034 | A cycle has to be visible before it can be waited on | accepted |
| 0035 | A promise in a contract is not an implementation | accepted |
| 0036 | The list the main screen has always asked for | accepted |
| 0037 | A rate that cannot be locked is not a reference rate | accepted |
| 0038 | Every promise is either served or owned | accepted |
| 0039 | A balance is the plan's positions, as of now | accepted |
| 0040 | A tax report is about plans, not about money that moved | accepted |
| 0041 | An invitation is spent by joining somebody, not by asking | accepted |
| 0042 | A token exists before anything requires one | accepted |
| 0043 | A group becomes a row, and a caller becomes required | accepted |
| 0044 | A group's history is derived, and membership is the whole condition | accepted |
| 0045 | Voiding is a transition, a refusal that names nothing, and idempotence by state | accepted |
| 0046 | A quote is locked by recording it, so the second door is removed | accepted |
| 0047 | The joiner is the caller, and the contract's own promise is what closed it | accepted |
| 0048 | A member, not a stranger, is who may add somebody to a group | accepted |
| 0049 | A member is who may write to a group's books | accepted |
