plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// RULE B22: booking depends only on kernel, ledger, planning and fx.
//
// This module exists for the same reason libs/planning does, and RULE B6 names the test: logic needed by
// two modules that may not see each other moves *down* into libs/* under a name that describes it. The API
// books an obligation when it is recorded; the settlement worker books a run when it executes; those two
// may never depend on each other.
//
// It is not in libs/ledger. Ledger may see kernel and nothing else (B2), and booking has to know what an
// obligation is - which is planning's type. It is not in libs/planning either: that module's README says
// what it owns, and it is "what must be settled" rather than "what the ledger records". Its
// ExternalRequirement and the entries that move it are different questions, and the module that answers
// the first kept the second out on purpose.
//
// fx is here because booking reads amounts off an FxQuote and reports the residual, which is an
// FxConversion. It does not multiply anything by a rate: RULE B19 keeps that in libs/fx, and the quote
// arrives already converted because that is the whole point of locking one.
dependencies {
    api(project(":libs:kernel"))
    api(project(":libs:ledger"))
    api(project(":libs:planning"))
    api(project(":libs:fx"))
}
