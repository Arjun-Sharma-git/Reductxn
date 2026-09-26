# libs/ledger

The double-entry model: exact money, an immutable balanced entry, and the verification that makes the
history trustworthy. Pure Java: no Spring, no JDBC, no HTTP, no clock, no persistence (RULE B5, P4).

## Purpose

Every state change is a `JournalEntry` of two or more `Posting`s that sum to zero in one currency. The
type is the guard: an entry cannot be constructed unbalanced, cannot mix currencies, and cannot be
modified after construction, so the invariants are properties of the model rather than rules a caller has
to remember.

## Public surface

Everything callers may touch lives in `io.reductxn.ledger.api` (RULE B10).

| Type | Role |
|---|---|
| `Money`, `Currency`, `Direction` | Exact amounts: an integer count of minor units plus the exponent needed to render it (P1) |
| `Posting` | One side of an entry: an account, a direction, a strictly positive amount (L2) |
| `JournalEntry` | The entry itself: balanced (L1), one currency (L6), immutable, with a derived hash (L4) |
| `EntryKind`, `FxRole` | What an entry records, and which side of a conversion it is |
| `EntryChain` | Verifies a chain, reporting every problem rather than throwing at the first |
| `FxLinkage` | Verifies FX pairing across chains (L9) |
| `Balances` | The projection: a position computed from postings, never stored (L5) |

`io.reductxn.ledger.internal` holds the canonical encoding and is not public surface.

## The schema

The module owns `journal_entries` and `postings`, and ships them as a migration beside this file
(`src/main/resources/db/migration/V301__ledger.sql`, the `3xx` range - see the README's neighbours for the
version scheme). Two things about it are worth knowing before reading it:

**The schema is a guard, not a description.** L2, L4, L6 and L9 are enforced by constraints and indexes rather
than by code that could forget them: a posting's amount is positive, a posting's currency must match its
entry's (a composite foreign key, because a check cannot read another table), at most one entry per chain may
claim a given predecessor, exactly one may start a chain, and a link id carries at most one base and one quote
entry. L1 and L3 are *not* there, and the migration says why: L1 is a property of a set of rows rather than of
one, and L3 needs a database role this project has not separated yet - a table's owner can always `UPDATE` and
`DELETE` however the grants read, so revoking from the application's role would change nothing until migrations
run as a different role.

**No `ledger_accounts` yet.** Section 5.5 assigns it here, but an account needs an owner and a type, and
neither exists: a member *is* its id today, and `fx_clearing` is a reserved constant in `libs/planning` that
RULE B2 stops this module from reading. So postings carry an account id with deliberately no foreign key.

## Where entries are written

This module composes entries; it does not store them. `apps/api`'s record-obligation use case builds the chain
head through a port this module defines and appends through a jOOQ adapter, in one transaction - the lock that
makes the chain safe is an advisory lock whose lifetime is that transaction. The chain a booking produces is
then verified from the rows by `EntryChain`, which is the arrangement that makes the ledger auditable without
the application: the verification reads the same rows an auditor would.

## Allowed dependencies

`libs/kernel` (RULE B2). Nothing else - not netting, not fx, not rails.

## Three decisions worth knowing before changing anything here

1. **The hash is derived, not stored.** A hash held as a field could disagree with the content it
   attests to; one computed from that content cannot. An entry whose hash does not match its rows is
   therefore not constructible at all, which is stronger than a rule saying it should not happen.
2. **The canonical encoding length-prefixes every free-form value and names its own version.** Without
   the length prefix, an idempotency key containing a field separator would produce the same bytes as a
   genuinely different entry - a collision constructed rather than found. Without the version tag,
   changing the encoding would invalidate every existing chain with no way to tell an old-but-valid hash
   from a corrupted one. `EntryHash.hexLength()` is a method rather than a constant because a dependency
   rule cannot see an inlined constant (MASTER-PLAN section 15).
3. **L6 is intact, and that is what keeps L1 expressible.** A cross-currency obligation is two linked
   single-currency entries (ADR 0012 point 3), not one mixed entry, so L1 stays a single sum per entry
   and both remain expressible as database constraints. There is deliberately no constructor that takes
   mixed postings, so the weakening is not available to a caller who has not read the ADR.

## What this module does NOT do

- It does **not** know what a rate is. It stores the two balanced entries a conversion produced and never
  multiplies an amount by anything (RULE B19, `docs/specs/ledger.md` section 5).
- It does **not** persist anything, and it does not enforce append-only. The model cannot modify an
  entry; the guarantee that nothing else can is the application's database role having no `UPDATE` or
  `DELETE` grant (ADR 0004), which is a schema property and tracked with it.
- It does **not** read a clock. Time is a parameter, so a stored entry replays identically.
- It does **not** own the `fx_clearing` account id. That belongs to `libs/planning`, which this module
  may not see (B2), so a projection takes the account id as an argument.

## Invariants

L1, L2, L4, L6 and L9 are asserted by tests. L3, L5, L7 and L8 are deferred in `PropertyCoverageTest`
with the mechanism each waits on - a schema for two of them, a write path for the other two - and those
tests deliberately carry no label for them rather than claiming an invariant this module cannot make true
on its own.

**Tests:** `./gradlew :libs:ledger:test`

**Spec:** `docs/specs/ledger.md`. **Owner:** ledger reviewer. **Review bar:** two reviewers.
