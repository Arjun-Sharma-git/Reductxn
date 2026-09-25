# ADR 0015 - `planning` computes the plan, `settlement` executes the run

- **Status:** accepted
- **Date:** 2026-09-25
- **Implemented:** `libs/planning` (package `io.reductxn.planning`), branch `refactor/libs-planning`
- **Related:** RULE B6, RULE B21; ADR 0012; MASTER-PLAN section 5.1

## Context

ADR 0012 moved per-currency composition *down* into a pure module, because `apps/api` and
`workers/settlement` both need it and may not depend on each other (B6). That module was created as
`libs/settlement`. `workers/settlement` already existed.

So two modules were both called `settlement`, and MASTER-PLAN section 5.1 described this as "the naming
split is deliberate". No split existed in the names. The document was asserting a distinction that the
identifiers did not carry - a smaller relative of the recurring problem in this repository, which is
claims that read as facts until someone tries to act on them.

Five concrete costs followed from it, and only the first is cosmetic:

1. **Prose.** "Settlement must not read a clock" and "settlement owns quorum" describe two modules with
   opposite properties - one pure and deterministic, one side-effecting and stateful. Every sentence
   needed the reader to infer which.
2. **Package roots.** The convention is that a module's package root is its last path segment:
   `libs/netting` is `io.reductxn.netting`. Both modules wanted `io.reductxn.settlement`, and two Gradle
   modules cannot share a package root.
3. **ArchUnit.** Every rule written against `io.reductxn.settlement..` matches **both** modules. That
   includes B21, which says settlement depends only on kernel, ledger, netting and fx - while the worker
   must depend on `libs/rails` and on persistence. This had not failed yet only because the worker has
   no sources. The first line of worker code would have failed a boundary rule that looked like a code
   problem.
4. **Error codes.** The accepted contract reserves the `settlement.*` namespace, and the example given is
   `settlement.fx_unfunded` - the funding gate, which is a run concern (S4). `DomainErrorCodeTest`
   requires a code's namespace to equal the owning module's package segment, and to be unique. Two
   modules sharing that segment makes "which module owns this code" unanswerable, which the error-code
   parity gate (I3) then cannot be built on.
5. **Tables.** The worker owns `settlement_cycles`, `settlement_runs` and `settlement_transfers`
   (RULE B17). Data-layer artifacts already said "settlement" and already meant the run.

## Decision

`workers/settlement` keeps its name. The library becomes **`libs/planning`**, package
`io.reductxn.planning`.

**The rule that follows: one module, one package root, and the root is the module's last path segment.**
No exceptions, so that a package pattern in a boundary rule always names exactly one module.

**Why the worker kept the word.** Every artifact that already existed attached "settlement" to the run:
the three tables, the `settlement.*` error namespace, `docs/specs/settlement.md` (which is about quorum,
idempotency, rails and funding), and Phase 2's deliverable, "settlement cycle". The library was the
outlier of five, so the library moved.

**Why `planning`.** The distinction that matters is plan versus run. `libs/netting` set the precedent
that a module may be named for the act rather than the artifact, and this module performs an act -
partitioning a cycle and composing per-currency plans. `planning` is short, matches that style, and
reads correctly beside the names it already has: `SettlementPlan`, `SettlementPlanner`,
`SettlementPlans`.

Type names are deliberately unchanged. The module is the act; `SettlementPlan` is the artifact. Renaming
the types to `Plan`/`Planner` would have removed the word "settlement" from the only place it is
unambiguous, and would have made a plan produced for settlement look like it had nothing to do with it.

## Consequences

- **B21 is scoped to exactly one module** and can now fail for a reason that means something. Verified
  by pointing the rule at the old package name: it fails with `failed to check any classes`, so a
  partially-completed rename is loud rather than silent.
- **`settlement.*` has one owner**, so `DomainErrorCodeTest` and the future parity gate have an answer
  to "which module declares this code", and the contract's examples stay true.
- Prose is unambiguous: planning computes the plan, settlement executes the run.
- The rename is recorded in MASTER-PLAN section 5.1 and Appendix A, and it touched 21 files - settings,
  one directory tree, twelve sources, four architecture-test files, and the docs that name the module.

## What becomes harder

- One more word in the vocabulary. A newcomer has to learn the plan/run distinction before the module
  names mean anything - though that distinction is the substance of ADR 0012, not a naming invention.
- `docs/specs/settlement.md` and `libs/planning` no longer share a name, so the spec-to-module mapping
  has to be stated rather than inferred from a filename. Section 5.1 and the module README say it.
- The module and its central type differ (`planning` exports `SettlementPlan`). Anyone grepping for
  "settlement" finds the type and the worker, and has to read one page to learn how they relate.

## Alternatives considered - and why rejected

**Rename the worker instead** (to `workers/runs` or similar). The cheapest option by file count, since
the worker is empty - and rejected on a constraint rather than a preference. The contract reserves
`settlement.*`, and `DomainErrorCodeTest` enforces that a code's namespace equals the owning module's
package segment. A module called `runs` could not own `settlement.fx_unfunded`. Either the contract's
namespace or the enforcement rule would have to give, and both are worse than renaming one directory.

**Keep both names and disambiguate by path.** Rejected: it leaves the shared package root, so B21 keeps
matching two modules and stays correct only by accident, and it leaves the ambiguity in prose - which is
where the cost is actually paid, every time someone writes or reads a sentence about settlement.

**`libs/settlementplan`.** Accurate and unambiguous, and rejected: it names a module after its own
central type, breaks the short-name convention that `kernel`, `ledger`, `netting`, `fx` and `rails` all
keep, and would have aged badly as the module grew past planning.

**`libs/plan`.** Rejected: `plan` is the artifact and the module is not one - it produces them. Reads
oddly beside `netting`, which is an act.

**Leave the composition in `workers/settlement` and have `apps/api` call the worker.** Rejected: this is
what B6 forbids, and ADR 0012 already rejected it. It forces two implementations of the same financial
arithmetic, which then drift, and a preview that can disagree with the run it previews.
