import org.gradle.api.tasks.PathSensitivity

plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// This module hosts repository-wide architecture tests. It sees every libs module, apps/api and
// workers/settlement, so ArchUnit can verify RULEs B1-B2, B5, B6, B8, B10, B12-B16, B18-B22 across the
// whole graph in one place.
//
// Every module listed here MUST also be listed below, or ArchUnit imports none of its classes and
// every rule that mentions it passes without checking anything. ArchUnitVisibilityTest exists to
// fail loudly when that happens, because the failure is otherwise invisible - and it caught exactly
// this when workers/settlement gained its first sources: B6 and the worker's layer rules ran, found
// no classes and failed, which is the honest version of "the check was blind".
//
// Spring is here for the same reason one level down: b15 names @Transactional and b20 names
// MessageSource, and a rule that cannot see the type it forbids is a rule that checks nothing.
//
// It has no production sources on purpose: test tooling and test fixtures only.
dependencies {
    testImplementation(project(":libs:kernel"))
    testImplementation(project(":libs:ledger"))
    testImplementation(project(":libs:netting"))
    testImplementation(project(":libs:fx"))
    testImplementation(project(":libs:planning"))
    testImplementation(project(":libs:booking"))
    testImplementation(project(":libs:rails"))
    testImplementation(project(":apps:api"))
    testImplementation(project(":workers:settlement"))

    testImplementation(libs.spring.tx)
    testImplementation(libs.spring.context)
    testImplementation(libs.jooq)
}

// ---------------------------------------------------------------------------
// Inputs Gradle cannot infer.
//
// Four of the tests here read files rather than classes: RuleCoverageTest parses the RULEs out of
// MASTER-PLAN.md and docs/specs, and checks that a Gradle guard is still declared in build-logic;
// PropertyCoverageTest parses the property tables and reads every module's test sources looking for
// assertion labels. None of that is on this module's classpath or in its own source tree, so Gradle
// sees an unchanged task and leaves it UP-TO-DATE.
//
// The consequence is quiet and specific, and it was observed rather than theorised: editing
// MASTER-PLAN.md and running `./gradlew build` reported pass counts of zero, because the coverage
// tests did not run at all. A docs-only edit that declares a new RULE or property would sail through
// a local build and only fail in CI. That is the same silent pass these tests exist to catch, one
// level up - so the files they read are declared as inputs.
// ---------------------------------------------------------------------------
val coverageTestInputs = listOf(
    rootProject.layout.projectDirectory.dir("docs"),
    rootProject.layout.projectDirectory.dir("build-logic/src/main/kotlin")
)

// SchemaOwnershipTest reads every module's migrations, which are outside this module's source tree for the
// same reason the docs are: Gradle cannot infer them, and an unread input means the test does not re-run.
//
// The patterns are module-anchored to match `javaTestTrees` below. A leading `**/` resolves the whole
// repository, including every module's `build` directory, and Gradle then fails the build for an implicit
// dependency on another task's output - which is a fair complaint about an input that claims to look
// everywhere. Anchoring keeps the same files and states where they are.
val migrations = fileTree(rootProject.layout.projectDirectory) {
    include("libs/*/src/main/resources/db/migration/*.sql")
    include("apps/*/src/main/resources/db/migration/*.sql")
    include("workers/*/src/main/resources/db/migration/*.sql")
    include("platform/*/src/main/resources/db/migration/*.sql")
}

val javaTestTrees = fileTree(rootProject.layout.projectDirectory) {
    include("libs/*/src/test/java/**/*.java")
    include("apps/*/src/test/java/**/*.java")
    include("workers/*/src/test/java/**/*.java")
    include("platform/*/src/test/java/**/*.java")
}

// PropertyCoverageTest checks that its claims about the i18n family still point at something, by reading the
// three files those checks are declared in: the catalog tool, a Vitest property test and the lint config.
//
// Same reason as the docs above, and the same failure if it is missed - Gradle sees an unchanged task, leaves it
// UP-TO-DATE and the test does not run at all. The patterns name those files rather than globbing `apps/web/src`,
// because a wider input would make this module re-run on every client edit for checks that read none of them;
// and the coupling is real in the other direction too: a new claim in ENFORCED_PROPERTIES that names another file
// has to be added here, or it will be checked once and never refreshed.
val webChecksReadByPropertyCoverageTest = fileTree(rootProject.layout.projectDirectory) {
    include("apps/web/tools/*.mjs")
    include("apps/web/eslint.config.js")
    include("apps/web/src/shared/money/money.test.ts")
}

tasks.withType<Test>().configureEach {
    inputs.dir(coverageTestInputs[0])
        .withPropertyName("docsReadByTheCoverageTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.dir(coverageTestInputs[1])
        .withPropertyName("conventionPluginsReadByRuleCoverageTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(javaTestTrees)
        .withPropertyName("testSourcesScannedForPropertyLabels")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(migrations)
        .withPropertyName("migrationsReadBySchemaOwnershipTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    inputs.files(webChecksReadByPropertyCoverageTest)
        .withPropertyName("webChecksNamedByPropertyCoverageTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
