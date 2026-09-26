plugins {
    id("reductxn.spring-conventions")
    id("reductxn.testing-conventions")
}

// A deployable with no HTTP surface, so no web starter: this module computes a run and writes its own tables,
// and an endpoint here would be a second write path to the same state.
//
// What it does NOT depend on is the point of the module graph. `workers/settlement` and `apps/api` never
// depend on each other (RULE B6), and `libs/planning` exists because of that: what the plan is belongs to both
// of them, so it moved down into a pure module rather than being implemented twice. The same applies to
// `libs/booking` for the entries a run books when it executes.
//
// `libs/booking` is depended on now, because a run books what it moves. The module's README has said since
// it was written that "the API books an obligation when it is recorded; the settlement worker books a run when
// it executes", and this is the second half of that sentence arriving: the booking *shape* stays in
// `libs/booking` (RULE B22), so two writers of the ledger's tables cannot disagree about what an entry is.
//
// `libs/rails` is depended on now, because execution landed: this module is the one that submits a run's
// transfers, so the seam to the outside world is called from here and from nowhere else. `apps/api` still
// neither knows nor cares that a rail exists (RULE B6) - it reads a run's tables through its own read model,
// and the money moves on the worker's side of the boundary.
dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.tx)
    // Jackson, for the tax rule's `params_json`. A web starter would bring it *and* the auto-configuration that
    // provides a configured ObjectMapper bean; this module has no web surface, so both are named here rather
    // than inherited from something it should not depend on. The Boot module rather than a hand-built mapper,
    // so the worker reads a jsonb column with the same settings the API writes it with.
    implementation(libs.jackson.databind)
    implementation(libs.spring.boot.jackson)

    implementation(project(":libs:kernel"))
    implementation(project(":libs:ledger"))
    implementation(project(":libs:netting"))
    implementation(project(":libs:planning"))
    implementation(project(":libs:fx"))
    implementation(project(":libs:booking"))
    implementation(project(":libs:rails"))
    implementation(project(":platform:persistence"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
