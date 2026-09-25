import org.gradle.api.tasks.PathSensitivity

plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// This module hosts repository-wide architecture tests. It sees every libs module and apps/api, so
// ArchUnit can verify RULEs B1-B2, B5, B8, B10, B12-B15, B18-B21 across the whole graph in one place.
//
// Every module listed here MUST also be listed below, or ArchUnit imports none of its classes and
// every rule that mentions it passes without checking anything. ArchUnitVisibilityTest exists to
// fail loudly when that happens, because the failure is otherwise invisible.
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
    testImplementation(project(":libs:rails"))
    testImplementation(project(":apps:api"))

    testImplementation(libs.spring.tx)
    testImplementation(libs.spring.context)
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

val javaTestTrees = fileTree(rootProject.layout.projectDirectory) {
    include("libs/*/src/test/java/**/*.java")
    include("apps/*/src/test/java/**/*.java")
    include("workers/*/src/test/java/**/*.java")
    include("platform/*/src/test/java/**/*.java")
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
}
