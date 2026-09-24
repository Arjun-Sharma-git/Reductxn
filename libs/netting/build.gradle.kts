plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B3: netting depends only on kernel and ledger.
// The solver is an `implementation` dependency: it is NOT part of our public surface.
dependencies {
    api(project(":libs:kernel"))
    api(project(":libs:ledger"))
    implementation(libs.timefold.solver)
}
