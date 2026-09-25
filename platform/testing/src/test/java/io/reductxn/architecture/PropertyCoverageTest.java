package io.reductxn.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the property families in the specs to the same standard as the RULEs.
 *
 * <p>{@link RuleCoverageTest} covers structure. This covers behaviour, and it exists because the two
 * halves had different amounts of discipline: the RULEs at least had a table claiming what was
 * enforced, while a property could be written into a spec, never tested, and nothing anywhere would
 * say so. {@code N5} sat in that state, and so do all of ledger's {@code L1-L9} and i18n's
 * {@code I1-I7}.
 *
 * <p><strong>Coverage is detected, not declared.</strong> A property counts as covered when a test
 * labels an assertion with its id:
 *
 * <pre>
 *   .as("X7: positions including fx_clearing sum to zero in %s", ...)
 * </pre>
 *
 * <p>That convention already existed in the property tests before this test did - fx labels 9 of its 9
 * assertions, netting 11 of 12 - so this reads a habit rather than imposing one. Detecting rather than
 * declaring means nothing has to be maintained for a covered property: only the exceptions need an
 * entry, and an exception is exactly the thing worth writing down.
 *
 * <p><strong>What this does not prove.</strong> It reads a label, not a proof. A test could label an
 * assertion {@code "N1:"} while asserting something else, and this would believe it. The guarantee is
 * narrower and still worth having: no property is <em>silently</em> untested, and no test claims an id
 * that no spec defines. Stated because a check that looks stronger than it is, is worse than one that
 * says what it covers.
 */
class PropertyCoverageTest {

    /** A property defined as a table row: {@code | N1 | sum(positions.netMinor) == 0 |}. */
    private static final Pattern PROPERTY_ROW =
            Pattern.compile("^\\|\\s*([NXLI]\\d+)\\s*\\|", Pattern.MULTILINE);

    /** A property asserted and labelled with its own id: {@code .as("X7: ...")}. */
    private static final Pattern PROPERTY_LABEL = Pattern.compile("\\.as\\(\"([NXLI]\\d+):");

    /**
     * Properties a spec introduces in prose rather than in its table.
     *
     * <p>netting.md defines N1-N8 in its property table and then introduces N9 in section 7, as part of
     * the pairwise-baseline decision (ADR 0011). A regex cannot tell a definition from a mention, so
     * this is listed rather than guessed at. Each entry is asserted to appear somewhere in the specs, so
     * a stale one fails rather than quietly widening the net.
     */
    private static final Set<String> INTRODUCED_IN_PROSE = Set.of("N9");

    /**
     * Properties with no test, each with the reason.
     *
     * <p>Every one of these is a decision rather than a backlog item. Most say the same thing in
     * different words - the implementation does not exist yet - and that is the point: a reader can see
     * at a glance that ledger's invariants are unimplemented rather than unverified, which are very
     * different problems.
     */
    private static final Map<String, String> DEFERRED_PROPERTIES = Map.ofEntries(
            Map.entry("N5", "the optimizer is never worse than the baseline. Tier 2 (ADR 0005) does not "
                    + "exist, so there is nothing to compare against. Testable the day one lands"),

            Map.entry("L1", "per entry, debits equal credits per currency. No journal entry type exists; "
                    + "libs/ledger is Money and Currency only"),
            Map.entry("L2", "amount_minor is strictly positive. Needs the posting type to constrain"),
            Map.entry("L3", "entries are immutable and corrections are compensating entries. Needs "
                    + "persistence, and the DB grants the spec names as the enforcement mechanism"),
            Map.entry("L4", "the entry hash chain. Needs the canonical encoding and the verifier job"),
            Map.entry("L5", "a balance is a projection, never stored truth. An architecture property "
                    + "over persistence that does not exist yet"),
            Map.entry("L6", "one currency per entry. Needs the entry type. Worth noting it is preserved "
                    + "unchanged by ADR 0012 rather than newly required"),
            Map.entry("L7", "the rounding residual is booked to fx_clearing, never dropped. The "
                    + "conversion half is X2 and passes; the booking half needs journal entries, so the "
                    + "invariant is half-met and the missing half is the accounting one"),
            Map.entry("L8", "fx_clearing is zero per currency at the end of every cycle. The plan "
                    + "exposes the position and the settlement properties assert it nets correctly, but "
                    + "'at the end of a cycle' needs a cycle"),
            Map.entry("L9", "FX linkage: every base entry has exactly one quote partner. Needs the two "
                    + "linked entries and the cross-chain verifier"),

            Map.entry("I1", "every en key exists in every shipped locale. There is no catalog and no "
                    + "second locale yet; ADR 0014 scaffolding is unstarted"),
            Map.entry("I2", "no unused keys in en. Needs the catalog, and only means something once it "
                    + "has keys that could go unused"),
            Map.entry("I3", "error-code parity, both directions. The server half is now enumerable - "
                    + "every DomainError declares its code as a constant - so this gate became possible, "
                    + "but en/errors.json does not exist and there is nothing to compare against"),
            Map.entry("I4", "format/parse round trip per locale. Needs the formatting layer"),
            Map.entry("I5", "formatting never changes the digits. Needs the formatting layer; the "
                    + "integer-minor-units rule it protects is already enforced for domain code by P1"),
            Map.entry("I6", "no double or float in the format/parse path. Needs that path to exist; P1 "
                    + "covers the domain half of the same concern today"),
            Map.entry("I7", "plural forms per locale. Needs the catalog and ru, which is the locale that "
                    + "makes this non-trivial (one/few/many/other)"));

    @Test
    void everySpecPropertyIsLabelledByATestOrExplicitlyDeferred() throws IOException {
        Set<String> defined = propertiesDefinedInTheSpecs();
        Set<String> labelled = propertiesLabelledInTheTests();

        assertThat(defined)
                .as("no property was found in docs/specs, so this test checks nothing - either the "
                        + "specs moved or they stopped writing properties as table rows")
                .isNotEmpty();

        List<String> silent = new ArrayList<>();
        for (String property : defined) {
            if (!labelled.contains(property) && !DEFERRED_PROPERTIES.containsKey(property)) {
                silent.add(property);
            }
        }

        assertThat(silent)
                .as("these properties are declared in docs/specs and no test labels an assertion with "
                        + "their id, so nothing would notice if they stopped being true. Label the "
                        + "assertion `.as(\"<id>: ...\")`, or add the property to DEFERRED_PROPERTIES "
                        + "with the reason it cannot be tested yet")
                .isEmpty();

        List<String> stale = DEFERRED_PROPERTIES.keySet().stream()
                .filter(property -> !defined.contains(property))
                .toList();
        assertThat(stale)
                .as("DEFERRED_PROPERTIES names properties that no spec declares any more")
                .isEmpty();

        List<String> noLongerTrue = DEFERRED_PROPERTIES.keySet().stream()
                .filter(labelled::contains)
                .toList();
        assertThat(noLongerTrue)
                .as("these properties are deferred but a test does label them, so the deferral is stale "
                        + "and is understating what is covered")
                .isEmpty();
    }

    @Test
    void everyPropertyLabelInTheTestsIsDefinedByASpec() throws IOException {
        Set<String> defined = propertiesDefinedInTheSpecs();
        Set<String> labelled = propertiesLabelledInTheTests();

        assertThat(labelled)
                .as("no property label was found in any test source, so this test checks nothing - "
                        + "either the tests moved or the `.as(\"<id>: ...\")` convention was dropped")
                .isNotEmpty();

        List<String> unknown = labelled.stream()
                .filter(property -> !defined.contains(property))
                .toList();

        assertThat(unknown)
                .as("a test labels an assertion with a property id that no spec defines, so the label "
                        + "points at nothing - usually a renumbering, or a typo that makes a real "
                        + "assertion look like it covers a property it does not")
                .isEmpty();
    }

    @Test
    void proseIntroducedPropertiesAreStillMentionedByASpec() throws IOException {
        String everySpec = readEverySpec();

        List<String> vanished = INTRODUCED_IN_PROSE.stream()
                .filter(property -> !everySpec.contains(property))
                .toList();

        assertThat(vanished)
                .as("INTRODUCED_IN_PROSE lists properties that no spec mentions any more, so the set "
                        + "would widen the net by adding ids that mean nothing")
                .isEmpty();
    }

    private static Set<String> propertiesDefinedInTheSpecs() throws IOException {
        Set<String> defined = new TreeSet<>(INTRODUCED_IN_PROSE);

        Matcher rows = PROPERTY_ROW.matcher(readEverySpec());
        while (rows.find()) {
            defined.add(rows.group(1));
        }
        return defined;
    }

    private static Set<String> propertiesLabelledInTheTests() throws IOException {
        Set<String> labelled = new TreeSet<>();

        for (Path source : testSources()) {
            Matcher labels = PROPERTY_LABEL.matcher(Files.readString(source));
            while (labels.find()) {
                labelled.add(labels.group(1));
            }
        }
        return labelled;
    }

    private static String readEverySpec() throws IOException {
        StringBuilder everything = new StringBuilder();

        try (Stream<Path> specs = Files.list(Repository.root().resolve("docs/specs"))) {
            for (Path spec : specs.filter(path -> path.toString().endsWith(".md")).sorted().toList()) {
                everything.append(Files.readString(spec)).append('\n');
            }
        }
        return everything.toString();
    }

    /**
     * Every Java test source in the repository, across every container.
     *
     * <p>All of them rather than only the owning module, because coverage legitimately crosses modules:
     * {@code X7} is an fx property and the assertion that labels it lives in {@code libs/settlement}.
     * A per-module check would report that as missing and be wrong.
     */
    private static List<Path> testSources() throws IOException {
        List<Path> sources = new ArrayList<>();

        for (String container : List.of("libs", "apps", "workers", "platform")) {
            Path containerRoot = Repository.root().resolve(container);
            if (!Files.isDirectory(containerRoot)) {
                continue;
            }
            try (Stream<Path> modules = Files.list(containerRoot)) {
                for (Path module : modules.filter(Files::isDirectory).toList()) {
                    Path javaSources = module.resolve("src/test/java");
                    if (!Files.isDirectory(javaSources)) {
                        continue;
                    }
                    try (Stream<Path> files = Files.walk(javaSources)) {
                        files.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
                    }
                }
            }
        }
        return sources;
    }
}
