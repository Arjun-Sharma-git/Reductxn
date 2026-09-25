plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B21: settlement depends only on kernel, ledger, netting and fx.
//
// This module exists because of RULE B6. `apps/api` needs the plan to preview what a cycle costs, and
// `workers/settlement` needs it to build the run, and those two may never depend on each other. So the
// composition moved *down* here, which is what B6 asks for when the same logic is needed twice.
//
// It could not go in libs/netting. Netting may only see kernel and ledger (B3), and ADR 0012 is
// explicit that the engine stays unaware of FX: every property N1-N9 assumes one currency at a time.
dependencies {
    api(project(":libs:kernel"))
    api(project(":libs:ledger"))
    api(project(":libs:netting"))
    api(project(":libs:fx"))
}