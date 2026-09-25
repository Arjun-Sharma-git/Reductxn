// reductxn - Gradle 9 multi-project build.
// The module graph here IS the architecture. See docs/MASTER-PLAN.md section 5.

rootProject.name = "reductxn"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("build-logic")

// --- Pure domain core. No Spring, no JDBC, no HTTP (RULE B5) ---
include(":libs:kernel")
include(":libs:ledger")
include(":libs:netting")
include(":libs:fx")
include(":libs:settlement")
include(":libs:rails")

// --- Deployables ---
include(":apps:api")

// --- Workers ---
include(":workers:settlement")

// --- Cross-cutting, non-domain ---
include(":platform:observability")
include(":platform:persistence")
include(":platform:security")
include(":platform:testing")
