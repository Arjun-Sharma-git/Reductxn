# Spec - Settlement lifecycle

**Owner:** backend reviewer · **Module:** `workers/settlement` · **Related:** ADR 0001, ADR 0008

## 1. Lifecycle

```
cycle:     open --> closing --> closed
run:       pending --> computing --> awaiting_approval --> executing --> done
                                              |                 |
                                              +-> rejected      +-> failed (partial)
                                              |
                                              +-> awaiting_fx_funding --> executing
transfer:  pending --> authorized --> submitted --> confirmed
                                  \-> failed
```

A run is created per `(group, cycle)` and contains **one currency partition per currency present in
the cycle**. Computing is asynchronous; the API returns `202 Accepted` with a run id, never a blocking
response. Approval is granted once for the whole run, not per currency, because the group is approving
one settlement of one set of commitments.

`awaiting_fx_funding` is reached when any currency partition leaves a non-zero `fx_clearing` position
(section 5). It is a real, blocking state, not a warning.

## 2. Quorum before real money moves

**RULE S1** - a run may only execute after quorum approval. Default policy: majority of members,
**and every member who owes a net amount must confirm personally**. One admin cannot move the
group's money.

## 3. Idempotency

**RULE S2** - execution is idempotent end to end:

- Each transfer carries a client-generated `rail_ref` derived from `(run_id, from, to, amount)`.
- Re-submitting the same transfer is a no-op returning the existing rail reference.
- Replaying an entire run must not change state. Verified by a Testcontainers replay test.

## 4. Rail interaction

Execution flows through `libs/rails`. Required behaviours of every adapter:

1. Submit is idempotent on `rail_ref`.
2. Every state transition is persisted before the next call is made.
3. Failures are classified: retryable (network, 429, 5xx) vs terminal (insufficient funds,
   rejected). Only retryable failures are retried, with exponential backoff and a circuit breaker.
4. A reconciliation sweep polls for transfers stuck in `submitted` beyond a threshold.

**RULE S3** - reduction partially applied. If some transfers confirm and others fail, the run is
`failed (partial)`, net positions are **not** marked settled, and the shortfall is surfaced to the
group as a credit-risk event. There is no silent rollback of confirmed money movement.

## 5. Cross-currency runs and FX funding (ADR 0012)

**Ownership split.** The *plan* - partitions, per-currency positions and transfers, the `fx_clearing`
position and the external requirement - is computed by `libs/planning` (MASTER-PLAN rule B21),
because both this worker and `apps/api` need it and they may not depend on each other (B6). This
worker owns the *run*: lifecycle, quorum, idempotency, rail submission and funding state. Nothing in
`libs/planning` reads a clock or a provider, so the same cycle always produces the same plan.

Each currency partition is netted independently, so the run carries **per-currency** net positions,
transfers, tax and transfer counts. There is no single global total, because adding yen to rupees is
not addition.

**`fx_clearing` is a participant, not a footnote.** A cross-currency obligation is planned as two legs
(ADR 0012 point 3), one in each book, with `fx_clearing` as the counterparty. It is netted like any
other participant so the transfers come out correct, then reported separately: transfers between two
members are member transfers, and any transfer touching `fx_clearing` is part of the group's external
requirement. Lumping the two together would present a group's FX exposure as if it were a member's
debt.

**RULE S4** - a run may not execute while any currency's `fx_clearing` position is non-zero and
unfunded. The engine cannot produce currency it does not hold (P2), so when a partition leaves a
residual clearing position the run transitions to `awaiting_fx_funding` and the group is asked to
supply it. Two ways to satisfy the requirement:

1. **Group-funded.** A member, or the group, brings the currency in from outside and the run is then
   funded. The requirement is shown as an exact amount per currency.
2. **Rail-funded.** The rail declares FX capability (`docs/specs/fx.md` section 9), performs the
   conversion, and the entries are marked `rail_performed_fx = true` with the rail's rate recorded.

**RULE S5** - the FX requirement is a reported number, never a hidden one. The "who pays whom" report
shows, per currency: member transfers, the `fx_clearing` leg, and the outstanding external requirement
if any. A group that cannot fund it is told plainly rather than shown a settled state it did not
reach.

## 6. Default handling

Netting concentrates obligation into fewer, larger transfers, which means a single default can
leave the group short. Mitigations, in order of preference:

1. Per-member credit limits enforced at obligation-recording time.
2. Optional pre-authorisation or hold at the rail.
3. Group-level reserve.
4. Explicit default policy agreed by the group, displayed in the UI.

**This is a product surface, not a footnote.**

## 7. Open questions

- Cycle frequency: fixed monthly vs per-group configured vs manual close.
- Whether the approval window expires and re-computes.
- How a group funds an external FX requirement operationally, and who is permitted to do it.
- Whether `awaiting_fx_funding` should expire and re-compute, since rates move while a group waits.
- Whether per-currency partitions may be executed independently once approved, which would let a
## 8. Implementation status

**Implemented in `workers/settlement`, tested against a container (5 tests):** opening a run for a
`(group, as-of)` pair, computing the plan through `libs/planning`, and recording it as rows - one partition per
currency, one net position per participant per currency, one transfer per movement with its RULE S2 rail
reference. The run's status is decided in the use case, not the adapter: `awaiting_approval`, or
`awaiting_fx_funding` when any currency's `fx_clearing` position is non-zero (RULE S4).

The tests verify the *stored* plan rather than the call: per-currency zero-sum read back from `net_positions`
(N1), every participant zeroed by applying the plan's own transfers (N2), the clearing account's residual, and
that a retry returns the same run and writes no second plan.

**Not implemented, and each for a stated reason:**

| Missing | Why it is not here |
|---|---|
| Quorum and approval (RULE S1) | Needs member confirmations, which are a group-membership concern owned by `apps/api`; the run's table holds the status it would move |
| Execution, and with it the funding gate's other side (RULE S4) | Nothing executes, so no run can be *stopped* from executing, and "funded" has no way to become true. The blocking state is real; the gate needs something to block |
| Rail submission and partial application (RULE S2, S3) | Needs `libs/rails` adapters. Until then every transfer row is `pending`, and `failed (partial)` is unreachable vocabulary rather than a state anything can reach |
| Reconciliation sweep (section 4.4) | Needs a submitted transfer to reconcile against, which needs a rail |
| Cycle frequency and closing (section 7) | A cycle is keyed by `(group, as-of)` today: an operator or a scheduler chooses the instant. Whether cycles close on a schedule, per group, or manually is an open question in section 7, and the current shape does not foreclose any of the three |
| Per-currency execution (section 7) | Would let a funded currency settle while another waits. Tempting for latency and risky for consistency, and the question is open |

**Deliberately not this worker's:** the plan itself (RULE B21, `libs/planning`), the entries a run books when it
executes (RULE B22, `libs/booking`), and anything a client reads. The worker has no public surface, so a run's
report reaches a group through `apps/api`'s read model over these tables - which is RULE B17's arrangement and
the reason the API and this module never depend on each other (RULE B6).

  funded currency settle while another waits. Tempting for latency, and risky for consistency.
