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

// ---------------------------------------------------------------------------
// RULE N1 guard: no wildcard imports.
//
// Stated in MASTER-PLAN Appendix A and, until now, gated nowhere - the appendix
// said so itself ("not yet gated in CI"). Same treatment as the P1 guard above,
// and for the same reason: a rule that only lives in a document is a comment.
//
// The substantive case is not style. `import java.util.*` makes our Currency
// ambiguous with java.util.Currency and stops the build with a confusing error,
// so the rule exists to keep that impossible rather than to be tidy.
//
// Scans tests as well as main sources. The P1 guard is scoped to domain code
// because that is where a float would do damage; import style is not.
// ---------------------------------------------------------------------------
val forbidWildcardImports by tasks.registering {
    group = "verification"
    description = "RULE N1: no wildcard imports in reductxn Java sources"

    val javaRoots = listOf(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/test/java")
    )
    val projectRoot = layout.projectDirectory.asFile

    // fileTree rather than inputs.dir, for the same reason as the P1 guard: a directory that does
    // not exist (platform/testing has test sources only) must contribute nothing, not fail.
    inputs.files(javaRoots.map { fileTree(it) { include("**/*.java") } })
        .withPropertyName("javaSourcesForImportCheck")
    outputs.upToDateWhen { false }

    doLast {
        val wildcardImport = Regex("""^\s*import\s+(static\s+)?[\w$.]+\.\*;""")

        val offenders = javaRoots
            .map { it.asFile }
            .filter { root -> root.exists() }
            .flatMap { root ->
                root.walkTopDown()
                    .filter { file -> file.isFile && file.extension == "java" }
                    .flatMap { file ->
                        file.readLines().withIndex()
                            .filter { (_, line) -> wildcardImport.containsMatchIn(line) }
                            .map { (index, line) ->
                                "  ${file.relativeTo(projectRoot)}:${index + 1}: ${line.trim()}"
                            }
                    }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RULE N1 violated - wildcard import:\n" +
                    offenders.joinToString("\n") +
                    "\nUse explicit imports: a wildcard java.util.* import also makes our Currency " +
                    "ambiguous with java.util.Currency and stops the build (MASTER-PLAN Appendix A)."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(forbidFloatingPointMoney)
    dependsOn(forbidWildcardImports)
}
