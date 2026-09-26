# libs/booking

**What** a commitment does to the ledger. Pure Java: no Spring, no JDBC, no HTTP, no clock, no provider
(RULE B5, P4).

## Purpose

A commitment is not a money movement. Recording one produces entries, and those entries are the product's
record of what was promised - so the decision of "which entries, in which currencies, through which
account" is a domain decision. This module makes that decision and nothing else.

## Why this module exists at all

RULE B6: `apps/api` and `workers/settlement` may not depend on each other, so logic needed by both moves
*down* into `libs/*` under a name that describes it. The API books an obligation when it is recorded; the
worker books a run when it executes.

It could not live in `libs/ledger`, which may see kernel and nothing else (B2) and would have to know what
an obligation is. It could not live in `libs/planning`, whose README states the boundary and keeps it: that
module owns **what must be settled**, and this one owns **what the ledger records**. Its `fx_clearing`
position and the entries that move it are different questions, and its README said so before this module
existed.

| Module | Owns |
|---|---|
| `libs/planning` | **What** must be settled: partitions, positions, transfers, the clearing position, the external requirement |
| `libs/booking` | **What that becomes in the ledger**: the entries, their currencies, their link, their chain |

## Public surface

Everything callers may touch lives in `io.reductxn.booking.api` (RULE B10).

| Type | Role |
|---|---|
| `Commitment` | A commitment's provenance: id, group, instant, idempotency key, author |
| `ChainHead` | Port: where a chain ends, asked per currency. The caller serialises and reads it |
| `Bookings` | The operation: `singleCurrency` books one entry, `crossCurrency` books a linked pair |
| `Booking` | A cross-currency result: both entries, the link, and the residual nothing can post |

## The three things worth knowing before changing anything here

1. **The debtor is credited and the creditor is debited.** A debit is positive, and a position's meaning
   depends on the account's type, so the question is which side holds a claim. The creditor does, and a
   claim is an asset. `Balances.netDebitOf` on the debtor therefore returns a negative figure, which reads
   correctly as "this member owes".
2. **Entry ids are derived from the commitment, not random.** A re-booked commitment produces the same ids
   and the same content, so it collides on the primary key instead of appending a second entry for a
   commitment nobody made twice. The ledger is append-only (L3) and cannot take an entry back.
3. **The residual is reported, not booked.** ADR 0013 makes it fractional and L2 requires a strictly
   positive integer posting, so a residual has no representation in the unit the ledger records in - it is
   not a small value, it is a value with no unit. Rounding it into a posting would invent or destroy value
   on every conversion (P1). `Booking.residual()` carries it out attached to its quote, which is what "never
   dropped" can mean while ADR 0013's open question stays open.

## What this module does NOT do

- It does **not** convert anything. The quote arrives already converted (RULE B19); this module never
  multiplies an amount by a rate.
- It does **not** read a clock, a database or a chain. Heads are passed in, and `occurredAt` comes from the
  caller, so two bookings of one commitment are byte-identical.
- It does **not** write anything, decide anything about netting, or know what a run is.
- It does **not** consume the residual. Accumulating or allocating it is the open question in ADR 0013.

## Allowed dependencies

`libs/kernel`, `libs/ledger`, `libs/planning`, `libs/fx` (RULE B22). Nothing else - and netting is excluded
by the rule even though `libs/planning` re-exports it.

## Tests

`./gradlew :libs:booking:test`

L9 is asserted with `FxLinkage` - the verifier that will run against the stored entries - rather than by
comparing link ids, so the test cannot agree with a broken verifier. L7's accounting half is asserted with
`Balances`, for the same reason.

**Spec:** `docs/specs/booking.md`. **Owner:** ledger reviewer.
