# ADR 0038 - Every promise is either served or owned

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `platform/testing/src/test/java/io/reductxn/architecture/ContractCoverageTest.java`, running from
  `./gradlew clean build` in the `api` CI job; the gate-table row and the plan's finding both name it
- **Related:** ADR 0035 (the slice that found the bug this exists for), ADR 0036 and ADR 0037 (the three reads it
  would have caught, closed before it existed), ADR 0031 (the pipeline that runs it), `RuleCoverageTest` and
  `PropertyCoverageTest` (the idiom it follows); RULE C1, C3, B20; P5

## Context

The bug: `GET /groups/{groupId}/members` sat in the contract for seven slices with nothing behind it, while three
client screens called it. **Both suites were green the whole time, and neither was wrong.** The client's browser
suite stubs the API from the contract's own generated types, so a stub generated from a promise passes by
construction; the server never asked for group members, so it had nothing to implement. The only visible symptom was
in development, where every screen that names a person fell back to the uuid that the client's `nameOf` documents as
the honest fallback - a fallback that made a broken read look like a deliberate one.

An audit of the contract's thirty operations against the controllers afterwards found three more reads in the same
state: the obligations list (the app's main screen, which had therefore never worked against a real API in this
build), the currency catalogue (the exponent every money-rendering component needs), and the reference rate. ADR 0036
and ADR 0037 closed all three. The audit also produced two wrong claims, and they are why this ADR is longer than the
check it describes:

- **A commit message that said the promises were all served**, written before anything had been audited and false the
  moment it was. The subject of a commit is a claim; this one had not been checked by anything.

## Decision

**A check in the build walks the contract's promised operations, matches them against the controllers' mappings, and
fails only when a gap appears that nobody has written down.** The seventeen known gaps are named in one map, each with
the screen, slice or decision that owns it. That is the whole decision; the rest of this section is the three
directions it checks and the reasons for the choices inside it.

**Three directions, because one direction is how a check like this lies.** A check that only asks "does every promise
have a handler?" would be satisfied forever by a baseline nobody ever prunes, so:

1. **Every promised operation is served or recorded.** An operation with no handler and no entry fails the build, with
   a message naming the operation and the fix.
2. **No recorded gap is now served.** A stale entry reads as coverage - the failure `RuleCoverageTest`'s second
   assertion exists for - and this is the direction that makes the list shrink: every handler that lands has to delete
   its own entry, in the same commit.
3. **No handler answers an operation the contract does not declare.** The opposite drift is not quieter: the client's
   types come from the contract, so an endpoint that is not in it cannot be called by any generated client at all.

**It reports rather than fails on what is owned.** The seventeen gaps are printed with their owners on every run and
included in any failure message. Failing on them would make the check the thing to switch off on the first day - a
build that fails on work the plan already lists as unbuilt is not information - which is the same reasoning the locale
check uses when it reports `ru` rather than failing on it. What fails is a *new* gap, and a baseline entry that has
gone stale.

## Consequences

- **The state it records:** thirty operations, thirteen served, seventeen with an owner. Those seventeen are balances,
  tax, groups, activity, invites, auth, identity, execute, void and webhooks - the screens the plan already lists as
  unbuilt - plus `POST /fx/quotes`, which is recorded as a **candidate for removal** rather than as a screen: the
  recording path locks its own quote and the obligation response carries it, so nothing calls that operation. ADR 0033
  removed three operations for the same reason, and "we will decide later" now has a line in a map with a build gate
  behind it, which is as close to a decision as a non-decision can be made.
- **It would have caught all four reads**, on the day each was written, which is the point. The three the audit found
  are closed; the mechanism that found them only existed afterwards.
- **Two false reports were made and fixed while writing it, and both are in the file.** The first read
  `@GetMapping(produces = ...)` as a path (the same mistake the audit's first version made, which is why the rule is
  written down twice). The second captured `("/preview")` with its parentheses, so three operations were reported as
  un-promised handlers and one as an unowned gap, all four from a missing strip. In both cases the repository's answer
  was knowable by reading two files, which is what made the report diagnosable - and is the reason the class carries
  the parsing decisions in its documentation rather than only the code.
- **It was made to fail on all three directions on purpose**, one at a time: a temporary contract path with no handler
  (direction 1), a temporary handler for a path the contract does not declare (direction 3), and a temporary entry for
  an operation that is already served (direction 2). Each failure named its own operation and nothing else.
- **What it cannot prove.** It compares a path and a verb. A handler that answers the right shape with the wrong
  status passes here and is caught by the contract test in the module that owns the operation. Nor does it know
  whether a *client* calls an operation - that is the question ADR 0036 asked, and it is answered by reading the
  client rather than by this check.

## Alternatives considered - and why rejected

**Fail on the seventeen known gaps as well.** It would make the gate absolute, and it would be switched off within a
day: the plan already lists those screens as unbuilt, so a build that fails on them reports what everyone knows. The
locale check settled the same argument the same way when it chose to report `ru` rather than to fail on it.

**Derive the owner from the plan.** Each gap already has a plan row or an ADR that covers its screen, so the check
could search for one and assert it exists. Rejected because it turns a decision into a search whose result nobody has
read - and because the useful property here is that an entry is *deleted* by the slice that closes it, which requires a
person to have written it.

**A script in `tools/` instead of a test.** The repository runs two test jobs in CI, and the `api` job runs
`./gradlew clean build`, so a Gradle test in `platform/testing` needs no new gate, no new job and no new toolchain -
and it can read the repository the way the other coverage tests already do. A script would have needed a third place
to be wired and a second way to read a repository root.

**Spring's request-mapping metadata instead of reading the sources.** It would be more faithful to what the server
actually does, and it cannot see the controllers: the module sees published classes and none of the sources, and
depending on `apps/api` from `platform` would break the boundary this repository's rules enforce. The two annotations
being read are also the ones a contributor writes, so a disagreement between this check and Spring's own metadata is a
sign the controller is written unusually, which is worth knowing.

**Making the client's suite fail on an operation the contract promises and the client does not call.** That is a
different and also real gap class - a promise nobody uses - and ADR 0036 found it by hand once. It is not this check,
and it is not the same failure: it is the opposite of a hole, and failing on it would make every future operation fail
the day it is added to the contract.


**The owners are written down in the check, not derived from the docs.** An owner is a decision about who closes a
gap, and deriving it from text elsewhere would replace a decision with a search. It also means that deleting the line
is part of doing the work rather than housekeeping, which is what makes the list a plan instead of a receipt.

**Both sides are read as text rather than by reflection.** The controllers are in `apps/api` and this module must not
depend on a deployable (`RuleCoverageTest` reads other modules' test sources and `SchemaOwnershipTest` reads migration
files for the same reason; the module's own README says this module depends on the modules it inspects and is never
depended upon). Spring's own metadata would be a second way to describe a mapping, and one that disagrees with the
source the moment a controller is written in a way the metadata does not cover.

- **A first version of the audit that read a mapping's arguments as a path**, so `@GetMapping(produces = ...)` became
  an operation at a path that does not exist and the report cried wolf. A tool that goes looking for gaps is not
  evidence until it has been made to fail on a case whose answer is known.

So the gap was not the four missing reads. It was that **nothing in the build had an opinion about whether the
document and the handlers agreed**, and the one artefact that the client trusts completely was the one artefact with
no check behind it.
