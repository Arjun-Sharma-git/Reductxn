plugins {
    id("reductxn.spring-conventions")
    id("reductxn.testing-conventions")
}

// The Spring Boot BOM is imported here rather than in the convention plugin, so the managed versions
// stay visible in the module that actually depends on Spring.
//
// `libs/planning` is what computes a settlement plan. The API never multiplies an amount by a rate
// (RULE B19) and never composes partitions itself (RULE B21) - it calls the pure modules and maps the
// result onto the contract.
dependencies {
    implementation(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.tx)

    implementation(project(":libs:kernel"))
    implementation(project(":libs:ledger"))
    implementation(project(":libs:netting"))
    implementation(project(":libs:planning"))
    implementation(project(":platform:persistence"))

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
}
