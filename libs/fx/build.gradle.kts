plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B18: fx depends only on kernel and ledger.
//
// Deliberately NOT on netting, and netting is deliberately not on fx. Rate logic must never be able
// to hide inside the netting engine, and the netting engine must never gain an FX awareness that
// would invalidate its per-currency proofs (ADR 0012).
dependencies {
    api(project(":libs:kernel"))
    api(project(":libs:ledger"))
}