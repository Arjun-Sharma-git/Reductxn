// Shared test stack: JUnit 5, AssertJ, jqwik (property tests), ArchUnit (architecture tests).
//
// Property tests are the required form for domain logic, not an optional extra:
// see docs/MASTER-PLAN.md section 8 and docs/specs/netting.md section 4.
plugins {
    `java-library`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
    testImplementation(libs.findLibrary("assertj").get())
    testImplementation(libs.findLibrary("mockito-core").get())
    testImplementation(libs.findLibrary("jqwik").get())
    testImplementation(libs.findLibrary("archunit-junit5").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform {
        // jqwik supplies the property-based engine; junit-jupiter the example-based one.
        includeEngines("junit-jupiter", "jqwik")
    }
}
