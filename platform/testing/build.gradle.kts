plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// This module hosts repository-wide architecture tests. It sees every libs module, so ArchUnit
// can verify RULEs B1-B8 and B10-B11 across the whole pure core in one place.
//
// It has no production sources on purpose: test tooling and test fixtures only.
dependencies {
    testImplementation(project(":libs:kernel"))
    testImplementation(project(":libs:ledger"))
    testImplementation(project(":libs:netting"))
    testImplementation(project(":libs:rails"))
}
