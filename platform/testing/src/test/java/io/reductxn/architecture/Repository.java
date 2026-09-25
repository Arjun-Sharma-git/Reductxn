package io.reductxn.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locating the repository from a coverage test.
 *
 * <p>Gradle runs tests with the working directory set to the module, so a test that reads docs or
 * another module's test sources has to walk upwards to find the root.
 *
 * <p>Shared rather than copied into each test, because a wrong path here does not throw - it produces
 * an empty comparison, which passes. That is the exact failure both coverage tests exist to prevent, so
 * it is not a sensible thing to have two independent copies of.
 */
final class Repository {

    private Repository() {
    }

    static Path root() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle.kts"))
                    && Files.exists(candidate.resolve("docs/MASTER-PLAN.md"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "could not find the repository root above " + Paths.get("").toAbsolutePath()
                        + ". Coverage tests read docs/ and src/test, so they must run from inside the "
                        + "repository");
    }
}
