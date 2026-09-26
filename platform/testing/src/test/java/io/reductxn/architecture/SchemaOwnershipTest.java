package io.reductxn.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE B17: each table has exactly one owning module - and the migration that creates it lives beside that
 * owner.
 *
 * <p>The rule had been deferred for the honest reason that there was no schema to check. There is one now,
 * and writing it turned up the thing a rule is for: Flyway keeps <em>one</em> version sequence per schema
 * however many locations it reads, so a {@code V1} in {@code apps/api} and a {@code V1} in
 * {@code libs/netting} are "more than one migration with version 1" and the application refuses to start.
 * "Migrations live beside their owning module" and "versions are a single sequence" are both true, and were
 * incompatible until this test existed.
 *
 * <p>The resolution is a version range per owner - {@code apps/api} owns 1xx, {@code libs/netting} owns
 * 2xx - which keeps migrations where RULE B17 puts them, keeps one history, and makes cross-module ordering
 * explicit rather than accidental. Both halves are checked here, because a convention nobody checks holds
 * until the next person adds a table.
 */
class SchemaOwnershipTest {

    /**
     * The version range each module owns. A module that starts owning tables has to be added here, which is
     * the point: the alternative is discovering the collision when the application will not start.
     */
    private static final Map<String, Integer> VERSION_RANGE_BY_MODULE = Map.of(
            "apps/api", 1,
            "libs/netting", 2
    );

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_][a-z0-9_]*)");

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * The repository root, from the working directory a Gradle test runs in - {@code platform/testing}, so
     * two levels up. Getting this wrong is silent in the worst way: the walk finds no migrations, and the
     * emptiness assertions are the only thing that would notice.
     */
    private static final Path REPOSITORY = Path.of("..", "..").toAbsolutePath().normalize();

    @Test
    void everyTableIsCreatedByExactlyOneModule() throws IOException {
        Map<String, List<String>> ownersByTable = new TreeMap<>();

        for (Map.Entry<Path, String> migration : migrations().entrySet()) {
            for (String table : tablesCreatedIn(migration.getKey())) {
                ownersByTable.computeIfAbsent(table, ignored -> new ArrayList<>())
                        .add(migration.getValue());
            }
        }

        assertThat(ownersByTable)
                .as("no migration creates a table, so this test checks nothing - either the migrations "
                        + "moved or the CREATE TABLE phrasing changed")
                .isNotEmpty();

        Map<String, List<String>> shared = new LinkedHashMap<>();
        ownersByTable.forEach((table, owners) -> {
            if (owners.size() > 1) {
                shared.put(table, owners);
            }
        });

        assertThat(shared)
                .as("RULE B17: these tables are created by more than one module, which means neither owns "
                        + "them and a cross-module read has no side to go through")
                .isEmpty();
    }

    @Test
    void everyMigrationVersionIsInItsOwnersRange() throws IOException {
        Map<Path, String> migrations = migrations();
        Map<String, String> wrong = new LinkedHashMap<>();

        assertThat(migrations)
                .as("no migrations exist at all, so this test checks nothing")
                .isNotEmpty();

        for (Map.Entry<Path, String> migration : migrations.entrySet()) {
            String fileName = migration.getKey().getFileName().toString();
            Matcher version = VERSION.matcher(fileName);

            assertThat(version.matches())
                    .as("%s is not named V<digits>__<description>.sql, so Flyway will not read it",
                            fileName)
                    .isTrue();

            Integer range = VERSION_RANGE_BY_MODULE.get(migration.getValue());

            assertThat(range)
                    .as("%s owns migrations but has no version range. Flyway keeps one version sequence "
                            + "across every location, so the range is what stops two modules claiming the "
                            + "same version. Add it to VERSION_RANGE_BY_MODULE.", migration.getValue())
                    .isNotNull();

            int versionNumber = Integer.parseInt(version.group(1));
            if (versionNumber / 100 != range) {
                wrong.put(fileName, migration.getValue() + " owns " + range + "xx, and this is "
                        + versionNumber);
            }
        }

        assertThat(wrong)
                .as("RULE B17: a migration is numbered outside its owner's range. Two modules sharing a "
                        + "version is a startup failure, not a warning")
                .isEmpty();
    }

    /** Every migration in the repository, keyed by path and valued by the module that owns it. */
    private static Map<Path, String> migrations() throws IOException {
        try (Stream<Path> candidates = Files.walk(REPOSITORY)) {
            Map<Path, String> found = new LinkedHashMap<>();

            candidates.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".sql"))
                    .filter(path -> path.toString().replace('\\', '/')
                            .contains("/src/main/resources/db/migration/"))
                    .sorted()
                    .forEach(path -> found.put(path, ownerOf(path)));

            return found;
        }
    }

    private static String ownerOf(Path migration) {
        String normalised = migration.toString().replace('\\', '/');
        String module = normalised.substring(
                0, normalised.indexOf("/src/main/resources/db/migration/"));

        // `<repository>/libs/netting` -> `libs/netting`, so the registry reads like the module graph.
        String repository = REPOSITORY.toString().replace('\\', '/');
        return module.startsWith(repository + "/") ? module.substring(repository.length() + 1) : module;
    }

    private static List<String> tablesCreatedIn(Path migration) throws IOException {
        List<String> tables = new ArrayList<>();

        for (String line : Files.readAllLines(migration)) {
            // These migrations carry comments explaining the version scheme, and one of those comments
            // discusses CREATE TABLE. Checking whether the statement is commented out matters more here than
            // it usually would.
            if (line.stripLeading().startsWith("--")) {
                continue;
            }

            Matcher matcher = CREATE_TABLE.matcher(line);
            if (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }

        return tables;
    }
}
