# ADR 0031 - The gates run somewhere that is not the machine that wrote the change

- **Status:** accepted
- **Date:** 2026-09-26
- **Implemented:** `.github/workflows/ci.yml` (two jobs: `api`, `web`); the "Spec sync" and "CI" rows of
  MASTER-PLAN section 8; `apps/web/tools/check-catalogs.mjs`'s header, which had recorded the codegen gate as unbuilt
- **Closes:** MASTER-PLAN's `CI | any runner | **Absent**` and `Spec sync | codegen diff check | RULE C3 violated`
- **Related:** ADR 0028 (the client's toolchain), ADR 0029 and ADR 0030 (whose two verification defects are the
  argument); RULE C3; P9

## Context

Every gate this repository has was real and every one of them ran from a developer's machine: `./gradlew clean build`
for the architecture rules, the rule-coverage test, the P1 float scan and 400 tests; `pnpm check` for types, lint,
the locale catalogs, 39 unit tests and a build; `pnpm e2e` for 22 browser tests including axe's contrast check. The
plan's own words for the situation were "the difference between a gate and a habit".

The last slice made the case concrete. Verification was run from a **fresh clone** rather than from the tree the work
was written in, and it found two defects that no working-tree run had found or could find:

- A test that passed by luck: it predicted PostgreSQL's `uuid` ordering with Java's `UUID.compareTo` (signed halves
  against unsigned bytes), which agrees about half the time.
- Three browser tests that failed only because a cold `vite preview` is slower than a warm one - the default
  five-second assertion ceiling was measuring the machine.

Neither was a product defect. Both were defects in *how the product was verified*, and both were invisible in the
only environment the gates had ever run in. A pipeline is precisely a fresh environment that runs the gates, so this
is the slice the previous one asked for.

## Decision

**1. One workflow, two jobs, each named after the command it runs.** `api - ./gradlew clean build` and
`web - pnpm check and pnpm e2e`. The two halves share nothing but a checkout, so they run in parallel and a failure
in either is useful immediately. Naming a job after its command means a red badge says what to type.

**2. The pipeline runs what a developer runs, and adds nothing of its own.** No CI-only flags, no test selection, no
"quick" mode: `build`, `check`, `e2e`, exactly as the gate table states them. A pipeline that runs a different
command from the one documented is a second definition of "passing", and the two drift.

**3. The environment is declared, not defaulted.** Temurin **25**, because the build's toolchain declares 25 and a
developer here has that distribution; Node **24** with the engine floor at 22; pnpm from `packageManager` in
`package.json` rather than a number in the workflow, so the pipeline and a developer cannot disagree about which pnpm
runs the gates. `pnpm install --frozen-lockfile`, because a resolution that changes in the pipeline but not in the
commit is the drift the lockfile exists to prevent.

**4. Two drift gates, because generated code that is committed is generated code that can be stale.** This is the row
the gate table listed as "codegen diff check" with nothing behind it (RULE C3):

- `pnpm codegen` then `git diff --exit-code -- src/shared/api/schema.d.ts`: the client's types are generated from the
  contract and committed, so a difference after regenerating means **the committed client is not the contract** - the
  one failure that makes every other client test beside the point, because they are all written against the generated
  types.
- `pnpm build` (which regenerates it) then `git diff --exit-code -- src/routeTree.gen.ts`: the route tree is generated
  by the router plugin and committed, so a difference means somebody added a route without building - stale in a way
  that is silent locally and breaks the next checkout.

**5. Docker is a requirement of the API job, and it is stated rather than discovered.** The API's integration tests
run against a real Postgres through Testcontainers - deliberately, because H2 would accept a dialect-adjacent version
of the SQL and prove the opposite of the thing under test. `ubuntu-latest` has Docker; the workflow says so, because
a runner without it would fail in a way that looks like a test failure.

**6. `CI` is left to the runner to set.** Playwright reads it and switches to one retry, the reporter that annotates
the commit, and `reuseExistingServer: false` - which means the browser suite exercises a **cold start**, the condition
that found the three defects. The pipeline should not set that variable itself: the framework's own reading of it is
the behaviour a developer can reproduce by exporting it.

**7. A failure keeps its evidence.** The Playwright report is uploaded on failure only, with a seven-day retention:
traces and screenshots are the only way to see what a browser test saw after the runner is gone. It is not a gate; it
is what makes a failure actionable.

**8. Least privilege, and no secrets.** `permissions: contents: read`, no credentials anywhere in the file. A pipeline
that could write to the repository would be a second way to change it, which this one has no reason to be - and the
moment a workflow needs a token is the moment its trust surface needs an argument.

**9. Cancellation, timeouts and a manual trigger.** A `concurrency` group cancels superseded runs of the same ref
(a force-push makes the older run pointless, and runner minutes are a real cost); `timeout-minutes` makes a hung
runner fail rather than occupy the queue; `workflow_dispatch` runs a pipeline against a branch without pushing a
commit to it.

## Consequences

- **What is verified, and what is not - stated plainly.** Every command the workflow runs was executed here, in the
  order the workflow runs them, from a **fresh clone**: `pnpm install --frozen-lockfile`, `pnpm codegen` followed by
  the spec-sync diff, `pnpm check`, the route-tree diff, `pnpm e2e` (22 passed), and `./gradlew clean build` (400
  tests). Both drift gates were **shown to fail on drift and pass when clean**: a path added to the contract made the
  first exit 1 with the generated diff printed, and a route tree taken from an older commit made the second exit 1
  with the missing route imports printed. What is *not* verified from here is the workflow's own plumbing - that
  GitHub schedules it, that the pinned action versions resolve, that the runner has the Docker the API job needs.
  There is no `gh` CLI in this environment and the Actions API is not reachable from it, so the first real run will be
  the first thing a human sees. The distinction matters: the *gates* are verified, the *runner* is not, and claiming
  otherwise would be the same mistake as trusting a passing test that never ran.
- **The pipeline cannot be the only place a gate runs.** `pnpm check` still runs the catalogs locally, `./gradlew
  build` still runs the rules' checks, and the plan's gate table still describes what a developer types. The pipeline
  adds the one thing a local run cannot: an environment nobody has been warming up.
- **The `reuseExistingServer` trap is now unreachable in the pipeline and still present locally.** A local `pnpm e2e`
  can be served by a preview server started earlier, and `vite preview` serves `dist` - so a *failed* build leaves the
  previous artefact in place and the suite tests the last build that succeeded. The pipeline starts from nothing, so
  it cannot happen there; `playwright.config.ts` says so where a developer will read it.
- **A test that passes by luck is now a class of defect with a name.** The tie-break test was fixed by giving explicit
  ids, and the shape of the mistake is written into its comment: an assertion that derives its expected answer from
  the same kind of value it is checking is testing a coincidence. The pipeline does not detect this class by itself -
  it detects it by running the suite somewhere the randomness is different, which is the cheapest available heuristic.
- **Two gaps this does not close, and both were already recorded.** There is still no dependency or secret scanning
  (the plan's Security row), and no coverage threshold enforcement in the pipeline (JaCoCo's numbers are produced by
  the build but nothing fails on them, since the thresholds live in the plan rather than in the build). Adding a gate
  is a decision with a reason; writing one into the pipeline without that reason would be exactly the kind of
  decoration this repository spends its rules avoiding.
- **The doc-rot this found.** `tools/check-catalogs.mjs`'s header described the codegen gate as "**Not built**" and
  said there was no CI configuration - true when written, stale the moment it changed, and fixed in the same commit.
  A header that documents the state of the world is a small liability; it is kept because the alternative (a check
  with no stated relationship to the plan's gate table) is worse.

## Alternatives considered - and why rejected

**A single job running both halves.** Simpler to read and slower to fail: nothing in either half can be fixed by
knowing about the other, and the API job is the longer one, so serialising them would make every web-only change wait
for a Gradle build.

**CI-only test selection (unit tests on a pull request, the browser suite on `main`).** Rejected because it makes the
browser suite's own drift invisible in exactly the place it matters - a pull request is where a regression should be
caught, and ADR 0030's three cold-start failures were in the browser suite. The runner minutes saved are real and are
not worth a gate that only fires after a merge.

**A cache of the Gradle and pnpm stores with a `restore-keys` fallback.** `setup-java` and `setup-node` already do
this. Hand-rolling it would be a second cache to invalidate.

**Pinning every action by digest rather than by tag.** The stricter supply-chain posture, and the right one for a
workflow with credentials; this one has none and runs read-only. It is recorded here as the thing to change first if
that ever stops being true.

**Running the pipeline against a branch before merging (a `pull_request` trigger only after a trial period).** The
workflow triggers on both `push` to `main` and every pull request, and the first run is the trial: there is nothing to
protect by keeping it quiet, and a pipeline nobody has watched run is a hypothesis.
