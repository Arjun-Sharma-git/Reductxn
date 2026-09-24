// RULE B9: this is the ONLY place compiler and test settings are declared.
// No module may hand-roll them. See docs/MASTER-PLAN.md section 5.2.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------
// RULE P1 guard: money is an integer minor unit. A binary floating-point value
// must never appear in domain code. This is a build failure, not a code review.
// ---------------------------------------------------------------------------
val forbidFloatingPointMoney by tasks.registering {
    group = "verification"
    description = "RULE P1: no floating-point type in src/main/java of a domain module"

    val sourceDir = layout.projectDirectory.dir("src/main/java")

    // Declared as a file collection rather than inputs.dir(...) on purpose: inputs.dir asserts that
    // the directory exists, which fails for modules that have test sources only
    // (platform/testing). A file tree tolerates an absent root and simply contributes nothing.
    inputs.files(fileTree(sourceDir) { include("**/*.java") })
        .withPropertyName("domainSourceFiles")

    outputs.upToDateWhen { false }

    doLast {
        val root = sourceDir.asFile
        if (!root.exists()) return@doLast

        val forbidden = Regex("""\b(double|float|Double|Float)\b""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, raw) ->
                        val line = raw.trimStart()
                        val isComment = line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")
                        !isComment && forbidden.containsMatchIn(raw)
                    }
                    .map { (index, raw) -> "  ${file.relativeTo(root)}:${index + 1}: ${raw.trim()}" }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RULE P1 violated - floating-point money in domain code:\n" +
                    offenders.joinToString("\n") +
                    "\nUse long minor units (see libs/ledger Money) instead."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(forbidFloatingPointMoney)
}
