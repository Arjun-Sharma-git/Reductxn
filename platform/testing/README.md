# platform/testing

Repository-wide test tooling and the executable form of the architecture. Test sources only: there is
no `src/main/java` here, on purpose.

## Purpose

Two jobs, and they are both about the same failure.

**1. The module graph as a test.** RULEs B1-B5, B8, B10, B11, B18, B19 and B21 are the architecture of
this repository. `ModuleBoundaryTest` asserts them, so violating one fails the build instead of
starting a code-review argument about folder structure.

**2. Stopping the rules from going quietly vacuous.** This is the job that earns its keep. Every
architectural failure found here so far has been a rule that *reported green while checking nothing*:

- A rule whose `that()` clause matched no imported class, failing as `emptyShould` - or passing, if
  `failOnEmptyShould` is ever relaxed.
- A rule with a non-empty subject whose *target* package was never imported, so nothing could appear to
  violate it. `b19` asserted that no module depends on FX while FX was invisible to ArchUnit.
- A rule under `noClasses()` whose *custom* condition was not inverted, so it passed against a
  deliberate sabotage.
- A rule the documentation claimed for years was enforced, and which did not exist at all (`b11`).

`ArchUnitVisibilityTest` guards the first two, `DomainErrorCodeTest` guards the error-code convention,
and every rule added here is verified by making it fail on purpose before it is trusted.

## Public surface

Nothing. This module is never depended upon; it depends on the modules it inspects.

## Allowed dependencies

`build-logic` conventions, plus `testImplementation` of every `libs/*` module.

**Every module a rule mentions must be listed here.** ArchUnit only sees classes on this module's
classpath, so a missing dependency makes the rule that names it silently vacuous rather than failing.
That has happened once already, which is why `ArchUnitVisibilityTest` exists to make it loud.

## Tests

`./gradlew :platform:testing:test`

| Class | What it checks |
|---|---|
| `ModuleBoundaryTest` | The RULEs of MASTER-PLAN section 5, as ArchUnit rules |
| `ArchUnitVisibilityTest` | That every module those rules name is actually visible to ArchUnit |
| `DomainErrorCodeTest` | That every `DomainError` has a well-formed, module-namespaced, unique code |

Two more are about the documents rather than the code, and they are the pair that keeps this repository
honest about itself:

- **`RuleCoverageTest`** - every RULE declared in MASTER-PLAN *or in `docs/specs`* is enforced by
  something named or explicitly deferred with a reason, and every claim of enforcement resolves to
  something that actually runs (an `@ArchTest` field that exists and is annotated, or a Gradle guard
  that exists).
- **`PropertyCoverageTest`** - every property in the specs is either labelled by a test assertion or
  explicitly deferred. Coverage is *detected*, not declared: a property counts as covered when a test
  labels an assertion `.as("X7: ...")`, so only the exceptions need maintaining.

A third is about a contract rather than a document, and exists because of the one bug that made both
suites agree a hole was fine:

- **`ContractCoverageTest`** - every operation `contracts/openapi/reductxn-api.yaml` promises either has
  a handler that matches it or appears in a map with the screen, slice or decision that owns it. The
  client's browser suite stubs the API from the contract's own generated types, so a promise with no
  handler passes both suites at once: `GET /groups/{groupId}/members` did exactly that for seven slices,
  with three screens calling it. It reads both sides as text rather than by reflection (this module sees
  other modules' published classes and none of their sources), it has seventeen recorded gaps, and an
  entry whose handler has since landed fails the build so the list cannot stop describing the repository.

`Repository` is the small shared helper they use to find the repository root from a test.

Both fail the build when a rule or property is added without a decision about how it is checked, and
both were verified by breaking them: undeciding `S1-S5`, removing a property label, labelling a
property no spec defines, and leaving a stale deferral each fail with the id named.

## What this module does not do

- It does not test domain behaviour. That belongs beside the code it tests, in each module's own
  `src/test`. Reading `libs/fx`'s conversion properties here would separate a claim from its proof.
- It does not check style, formatting or imports as such. RULE N1 (no wildcard imports) is enforced by
  a Gradle verification task in `build-logic`, alongside the RULE P1 floating-point guard, so it runs
  for every module rather than only for the ones with architecture tests.
