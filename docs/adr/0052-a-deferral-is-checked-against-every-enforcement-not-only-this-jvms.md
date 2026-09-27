# ADR 0052 - A deferral is checked against every enforcement, not only this JVM's

## Status

Accepted.

## Context

`PropertyCoverageTest` answers one question: for every property a spec declares, is something claimed to
cover it? It answered by detecting coverage - a test labels an assertion with the property's id - and for
three slices the i18n family sat in its `DEFERRED_PROPERTIES` saying things like "needs the catalog, and
only means something once it has keys that could go unused".

Every one of those reasons was false by then. `docs/specs/i18n.md` section 10 said all seven were
**Implemented** and named the gate for each: `apps/web/tools/check-catalogs.mjs` for I1, I2, I3 and I7, a
Vitest property test over four locales for I4 and I5, and two ESLint rules for I6. The catalogs existed,
`ru` shipped with 378 keys, and the lint config had been failing builds on `Intl.NumberFormat` outside
`shared/money` for months.

So two documents contradicted each other and no test could see it, because a deferral was only ever compared
against **JVM assertion labels** - and the evidence for these seven is a Node script, a Vitest test and a
lint rule. MASTER-PLAN's findings table recorded it as the one instance a documentation pass found and
deliberately left standing: correcting it meant teaching that gate to claim an enforcement outside the JVM,
"which is the move `RuleCoverageTest` already made when it began reading a named test's source. Recorded
rather than half-done: it is a slice, not a sentence."

This is that slice. Its siblings set the pattern: `RuleCoverageTest` learned to read a **Java test's source**
when S1-S5 turned out to be enforced by tests in `workers/settlement` that reflection could not reach, and
`SchemaOwnershipTest` reads **migration files** because table ownership is a fact about a repository rather
than about what a JVM can load.

## Decision

**`PropertyCoverageTest` accepts a second kind of evidence: a file, and the declaration inside it.**

Coverage is now "a test labels it **or** `ENFORCED_PROPERTIES` claims it", and a deferral is stale if
*either* is true - which is the widening that would have caught this. The claim's shape is

```
<repo-relative path>#<kind>:<anchor>
```

with three kinds, because the three files declare a check differently and each anchor is the **declaration
rather than a mention**:

| Kind | Anchor | Resolves when |
|---|---|---|
| `banner` | `apps/web/tools/check-catalogs.mjs#banner:I1` | the file has a `--- I1` section marker, which heads the block that does the work |
| `test` | `apps/web/src/shared/money/money.test.ts#test:I4` | the file declares `it('I4…` or `test('I4…`, which is what the runner registers |
| `rule` | `apps/web/eslint.config.js#rule:no-restricted-properties` | the config carries `'no-restricted-properties':`, which is what turns the rule on |

Three directions are enforced, in two tests:

- **a claim that no longer resolves fails** - a file that moved, a test renamed, a rule switched off, an
  anchor kind that does not exist. This is the direction that makes the map worth having: without it, the map
  is prose in a Java string.
- **a claim naming a property no spec declares fails**, and **a property in both maps fails** - the overlap
  direction `RuleCoverageTest` gained when a rule turned out to be enforced and deferred at once.
- **a deferral that something enforces fails**, which is the original defect, fixed by widening the check
  rather than by remembering.

And because these files are outside the module, they are **declared as Gradle inputs**: without that, editing
the catalog tool would leave this task `UP-TO-DATE` and the check would not run at all - the silent skip
found in an earlier slice, which is why the docs, the test sources and the migrations are declared too.

## What this proves, and what it does not

It proves that the file still exists and still declares that check, so a claim cannot outlive the thing it
names. It does **not** prove the check asserts what the property says - a banner could head a block that does
nothing - which is the same limitation `RuleCoverageTest` states about its own source references. The claim
remains a written decision; what became mechanical is that the evidence is still there.

## Alternatives rejected

- **Naming the gate in prose in the deferral map** - what it did for three slices. Unverifiable, and the
  reason this ADR exists.
- **An out-of-scope family entry, the way `RuleCoverageTest` handles F1-F12** - right for a family whose rules
  live in another toolchain, wrong here. The property *ids* are in the specs, each names a specific gate, and
  the map's job is to answer "what covers this". An entry saying "not this module's to check" would be true
  and useless: the answer to I4 is a test, not a shrug.
- **Running the web gates from Gradle** - a Node toolchain inside the Java build, asserting cross-language
  wiring rather than the claim itself, and making the architecture module depend on pnpm and Node.
- **Checking only that the named file exists** - passes when the test inside it is renamed, which is the most
  likely way this evidence disappears.
- **A bare substring search for the id** - would be satisfied by this file's own header listing the family.
  The sabotage below found the *prefix* form of exactly this in the first draft of the banner check.
- **Moving the i18n claims into `RuleCoverageTest`** - nothing there is about behaviour, and the RULE/property
  split is what lets each map hold one kind of claim.

## Consequences

- **The map is load-bearing, and that was checked rather than assumed**: deleting the I7 entry makes the
  coverage test fail naming `I7` as silent. A second map that nothing depended on would be the same defect in
  a new place.
- **A new claim naming another file must be added to `build.gradle.kts`'s input list.** Stated in the build
  file where the list is, because the failure is silent: the claim would be checked once and never refreshed.
- **`DEFERRED_PROPERTIES` now holds three entries** - N5, L3 and L5 - down from ten. Three families have
  emptied it in sequence: the ledger's as its types landed, booking's as the write path arrived, and i18n's as
  this gained a way to see the evidence.
- **Two more stale sentences were found while checking the claims**, both about CI and both from the family
  the earlier pass was hunting: `docs/specs/i18n.md` section 10 said "none of it runs in CI: there is no
  pipeline configuration in this repository", and a MASTER-PLAN finding about a cold start ended "there is
  still no pipeline in this repository". A pipeline has existed since ADR 0031.

## Evidence

Seven sabotages, one at a time, each reverted from a pristine copy with the restoration verified by hash.
Six should fail and did, each message naming the property and the reason:

| Sabotage | Result |
|---|---|
| a claim pointing at a file that does not exist | FAILED - "no file at …" |
| the `--- I2` banner renamed to `--- I2x` | **passed at first**; FAILED once the checker was fixed |
| the `I4` test renamed in `money.test.ts` | FAILED - "no test declared with a name starting 'I4' …" |
| **every** `no-restricted-properties` key misspelt | FAILED - "the rule … is not turned on in …" |
| the `I7` entry deleted from `ENFORCED_PROPERTIES` | FAILED - the coverage test names `I7` as silent |
| `I1` added to `DEFERRED_PROPERTIES` as well | FAILED twice: the stale-deferral check and the overlap check |
| an anchor kind spelled `bannner` | FAILED - "unknown anchor kind 'bannner', so nothing checked this claim" |

**Two of those seven are worth more than the other five.** The banner case passed, and the reason was a defect
in the checker rather than in the claim: the banner anchor was a `contains`, so `--- I2x` satisfied a claim of
`--- I2` - the prefix form of the very looseness this ADR rejects substring search for. It is a word-boundary
match now, and the case fails. The other was a flawed experiment rather than a finding: the first attempt at
the rule case changed only one of the config's two declarations of `no-restricted-properties`, and the claim
correctly still resolved against the other one - so the experiment was repeated with every occurrence renamed.

