# Spec - Settlement lifecycle

**Owner:** backend reviewer · **Module:** `workers/settlement` · **Related:** ADR 0001, ADR 0008

## 1. Lifecycle

```
cycle:     open --> closing --> closed
run:       pending --> computing --> awaiting_approval --> executing --> done
                                              |                |
                                              +-> rejected     +-> failed (partial)
transfer:  pending --> authorized --> submitted --> confirmed
                                  \-> failed
```

A run is created per `(group, currency, cycle)`. Computing is asynchronous; the API returns
`202 Accepted` with a run id, never a blocking response.

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

## 5. Default handling

Netting concentrates obligation into fewer, larger transfers, which means a single default can
leave the group short. Mitigations, in order of preference:

1. Per-member credit limits enforced at obligation-recording time.
2. Optional pre-authorisation or hold at the rail.
3. Group-level reserve.
4. Explicit default policy agreed by the group, displayed in the UI.

**This is a product surface, not a footnote.**

## 6. Open questions

- Cycle frequency: fixed monthly vs per-group configured vs manual close.
- Whether the approval window expires and re-computes.
- Multi-currency runs within one cycle.
