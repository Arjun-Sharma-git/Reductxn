// Spring Boot conventions for deployable modules.
//
// Applied by apps/api and workers/settlement once they gain sources (Phase 1).
// NEVER applied by libs/*: the pure domain core stays framework-free (RULE B5 / P4),
// which is what lets an auditor run the netting engine without a container.
//
// The Spring Boot BOM is imported by each deployable's own build file, not here, so that
// managed versions stay visible in the module that actually depends on Spring.

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("reductxn.java-conventions")
}
