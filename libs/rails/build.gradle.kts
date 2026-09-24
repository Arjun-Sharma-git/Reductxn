plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B4: rails depends only on kernel and ledger.
// No HTTP client type may leak through the public port.
dependencies {
    api(project(":libs:kernel"))
    api(project(":libs:ledger"))
}
