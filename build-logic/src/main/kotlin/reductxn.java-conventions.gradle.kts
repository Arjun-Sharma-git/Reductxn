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
                    "\nUse long minor units (see libs/ledger Money) instead." +
                    "\nThis is a text match over every non-comment line on purpose, so it cannot miss a" +
                    "\nreal type - which means the same words are also flagged inside a message string." +
                    "\nIt fired once on \"not double entry\" in an exception message. If the flagged line" +
                    "\nhas no floating-point type on it, reword the line rather than removing the check."
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

// ---------------------------------------------------------------------------
// Guard: no source file may be excluded by .gitignore.
//
// A local checkout has every file, ignored or not, so a source directory excluded
// by a stray .gitignore pattern compiles, tests and builds green - and is simply
// absent from every clone. That is not hypothetical: it happened while adding
// apps/api. A bare `out/` (the usual Java idiom, meant for an IDE's build
// directory) matches a directory named `out` at ANY depth, including `port/out`,
// which MASTER-PLAN section 5.4 prescribes by name. Two source files were left
// out of a commit, `git add -A` reported nothing, and the local build passed.
// The only signal was a fresh clone failing to compile.
//
// This task is that signal, moved to where everyone sees it on every build.
// Skips when git cannot answer (no repository, no git) rather than failing,
// because a source tarball has no index to consult and is not a broken repository.
// ---------------------------------------------------------------------------
val forbidIgnoredSources by tasks.registering {
    group = "verification"
    description = "No src/**/*.java may be excluded by .gitignore"

    val projectRoot = layout.projectDirectory.asFile
    val javaRoots = listOf(
        layout.projectDirectory.dir("src/main/java"),
        layout.projectDirectory.dir("src/test/java")
    )

    inputs.files(javaRoots.map { fileTree(it) { include("**/*.java") } })
        .withPropertyName("javaSourcesForIgnoreCheck")
    // The rules are an input too: editing .gitignore changes the answer, and a docs-only edit to it
    // must re-run this rather than leaving the task UP-TO-DATE.
    inputs.files(rootProject.layout.projectDirectory.file(".gitignore"))
        .withPropertyName("gitignoreReadByTheIgnoreCheck")
    outputs.upToDateWhen { false }

    doLast {
        val sources = javaRoots
            .map { it.asFile }
            .filter { root -> root.exists() }
            .flatMap { root ->
                root.walkTopDown().filter { file -> file.isFile && file.extension == "java" }.toList()
            }

        if (sources.isEmpty()) return@doLast

        val git = ProcessBuilder(listOf("git", "check-ignore", "--stdin"))
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()

        git.outputStream.bufferedWriter().use { stdin ->
            sources.forEach { stdin.write("${it.relativeTo(rootProject.projectDir).path}\n") }
        }

        val ignored = git.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        val exit = git.waitFor()

        // 1 means nothing matched, which is the good case. 128 means git could not answer.
        if (exit == 0 && ignored.isNotEmpty()) {
            throw GradleException(
                "These source files are excluded by .gitignore, so they are absent from every clone " +
                    "while a local build passes:\n" +
                    ignored.joinToString("\n") { "  $it" } +
                    "\nAnchor an over-broad pattern (a bare `out/` matches `port/out`), or remove it. " +
                    "`git add -A` does not warn about ignored paths."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(forbidFloatingPointMoney)
    dependsOn(forbidWildcardImports)
    dependsOn(forbidIgnoredSources)
}
