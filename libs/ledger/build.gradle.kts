plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B2: ledger depends only on kernel.
dependencies {
    api(project(":libs:kernel"))
}
