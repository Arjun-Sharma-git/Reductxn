plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// This module hosts repository-wide architecture tests. It sees every libs module, so ArchUnit
// can verify RULEs B1-B2, B5, B8, B10, B18 and B19 across the whole pure core in one place.
//
// Every module listed here MUST also be listed below, or ArchUnit imports none of its classes and
// every rule that mentions it passes without checking anything. ArchUnitVisibilityTest exists to
// fail loudly when that happens, because the failure is otherwise invisible.
//
// It has no production sources on purpose: test tooling and test fixtures only.
dependencies {
    testImplementation(project(":libs:kernel"))
    testImplementation(project(":libs:ledger"))
    testImplementation(project(":libs:netting"))
    testImplementation(project(":libs:fx"))
    testImplementation(project(":libs:settlement"))
    testImplementation(project(":libs:rails"))
}
