plugins {
    id("reductxn.java-conventions")
    id("reductxn.testing-conventions")
}

// The data-access stack, exposed as `api` so a deployable that depends on this module gets a working
// DataSource, jOOQ and Flyway rather than four separate dependencies to keep in step with each other.
//
// The Spring Boot BOM IS imported here, unlike in the deployables' own build files. Deliberately: the
// versions of spring-jdbc, jOOQ, HikariCP, Flyway and the driver are only correct in combination, and
// Boot is what knows the combination for the line we target (ADR 0002). A consumer importing the same
// BOM agrees rather than conflicts, so this exports an agreement rather than an opinion.
//
// Note what is NOT here: no Spring Boot plugin, so no bootJar and no bootRun. This module is a library
// that deployables depend on, and a library that can be run is a second thing to deploy.
dependencies {
    api(platform(libs.spring.boot.bom))

    api(libs.spring.boot.starter.jooq)
    // Flyway's autoconfiguration is its own Boot module in Boot 4, not part of spring-boot-autoconfigure.
    // The starter brings flyway-core; the Postgres database module is separate, because Boot 4 splits
    // Flyway's per-database support.
    api(libs.spring.boot.starter.flyway)
    api(libs.flyway.postgres)

    // The driver is a runtime concern: nothing in this repository compiles against Postgres types.
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
