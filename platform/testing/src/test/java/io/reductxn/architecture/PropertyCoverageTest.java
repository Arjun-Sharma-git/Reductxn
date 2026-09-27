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
 * say so. {@code N5} sat in that state, ledger's {@code L1-L9} and i18n's {@code I1-I7} were listed
 * with it. Ledger's list has since fallen from nine to four as the types that verify it landed, and
 * i18n's seven left it when this learned to read evidence outside the JVM (ADR 0052) - every one was
 * implemented all along, and the map could not see a Node script, a Vitest test or an ESLint rule.
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

            // Ledger. Six of the nine gained tests as the entry, posting and hash-chain types landed and as
            // booking arrived; the three below are what remains, and each names the mechanism the spec
            // relies on rather than merely the work. That distinction is the point of this map: L3 and L5
            // are waiting on a schema, L8 on the run that executes a cycle, and none of them on more types.
            //
            // L7 left this map when booking landed. It used to say the booking half needed a write path;
            // what booking can assert, it now does - each side posts the exact amount, and the residual
            // leaves the booking attached to its quote instead of being rounded into a posting. The half
            // that remains is the cycle sum, which is L8's question rather than a separate one, so it moved
            // there rather than being restated.
            // L8 left this map when the settlement worker arrived, and its remaining half - "fx_clearing is
            // zero, per currency, at the end of every cycle" - was the last thing it deferred. That half is
            // asserted now: `SettlementBookkeepingTest` books a cross-currency commitment, executes the run,
            // and reads the clearing account's position back from the stored postings with `Balances`. It could
            // not be asserted before the settlement posting existed, which is the sequence worth remembering:
            // a property that waits on a mechanism is deferred for a reason rather than for ever - and this
            // comment is the record of which reason it was.
            Map.entry("L3", "entries are immutable and corrections are compensating entries. The model "
                    + "half is done - an entry is an immutable record over an unmodifiable posting list, "
                    + "and a compensating entry returns an account to zero - but the spec names the "
                    + "enforcement as the database grants, so the tests carry no L3 label rather than "
                    + "claiming an invariant the model cannot make true on its own"),
            Map.entry("L5", "a balance is a projection, never stored truth. The projection is built: "
                    + "Balances.netDebitOf sums signed postings, order-independently, and a compensated "
                    + "entry returns zero. The 'never stored' half claims that no balance column exists "
                    + "anywhere, which is a property of a schema rather than of this module")

            // I1-I7 left this map in ADR 0052, and the reason they sat here is worth keeping. Each said the
            // same thing in different words - "needs the catalog", "needs the formatting layer" - and all of
            // it landed across ADRs 0014, 0028 and 0029: the catalogs, the money and rate formatting, the
            // catalog check itself, the lint rules. What they were missing was never the implementation; it
            // was a way for this map to name one, because coverage was only ever detected from Java assertion
            // labels and the evidence for these is a Node script, a Vitest test and an ESLint rule. They are
            // in ENFORCED_PROPERTIES now, each with the anchor it is declared under - which is the
            // contradiction this test could not see for three slices, while docs/specs/i18n.md said
            // "Implemented" for all seven and this map said the layer did not exist.
            );

    /**
     * Properties something outside this JVM enforces, each with the evidence that it does.
     *
     * <p><strong>Why this exists.</strong> Coverage used to be detected from one thing only: an assertion
     * label. That works for a property a Java test proves and cannot see one a browser tool proves, so the
     * i18n family sat in {@link #DEFERRED_PROPERTIES} saying "there is no catalog and no second locale yet"
     * while {@code docs/specs/i18n.md} section 10 said all seven were <em>Implemented</em> and named the gate
     * for each. Two documents contradicting each other, and no test able to see it - because a deferral was
     * only ever checked against JVM labels. Recorded in MASTER-PLAN's findings table and corrected here.
     *
     * <p><strong>References rather than prose, for the reason {@link RuleCoverageTest} uses them.</strong> A
     * claim of enforcement nothing verifies is the bug both maps were written for. The shape is
     * {@code <repo-relative path>#<kind>:<anchor>}:
     *
     * <pre>
     *   apps/web/tools/check-catalogs.mjs#banner:I1              the section marker the check is implemented under
     *   apps/web/src/shared/money/money.test.ts#test:I4          a test whose declared name carries the id
     *   apps/web/eslint.config.js#rule:no-restricted-properties  a rule the config turns on
     * </pre>
     *
     * <p><strong>Three kinds because the three files declare a check differently, and each anchor is the
     * declaration rather than a mention.</strong> That distinction is not pedantry: a bare search for
     * {@code I4} would match this file's own header listing the family, and a search for a tool's name would
     * match its usage comment. A banner heads the block that does the work, a test name is what the runner
     * registers, and a quoted rule key is what turns an ESLint rule on.
     *
     * <p><strong>What this proves and what it does not.</strong> It proves the file exists and still declares
     * that check, so a claim cannot outlive the thing it names - which is the whole failure it was built for.
     * It cannot prove the check asserts what the property says, exactly as {@link RuleCoverageTest} says of
     * its own source references. The claim remains a written decision; what is mechanical is that the
     * evidence is still there.
     */
    private static final Map<String, List<String>> ENFORCED_PROPERTIES = Map.ofEntries(
            // The catalog tool, one section per property. Its banners are its own structure - `--- I1` heads
            // the loop that compares each locale against the source - so a reordering that drops one fails here.
            Map.entry("I1", List.of("apps/web/tools/check-catalogs.mjs#banner:I1")),
            Map.entry("I2", List.of("apps/web/tools/check-catalogs.mjs#banner:I2")),
            Map.entry("I3", List.of("apps/web/tools/check-catalogs.mjs#banner:I3")),
            Map.entry("I7", List.of("apps/web/tools/check-catalogs.mjs#banner:I7")),

            // The two that are round-trip and digit properties, in one Vitest file whose test names carry the
            // ids. Naming them separately is deliberate: I4's generator could be weakened to values that
            // survive any formatter, and I5 is the one that catches a `toLocaleString(amount / 100)` refactor.
            Map.entry("I4", List.of("apps/web/src/shared/money/money.test.ts#test:I4")),
            Map.entry("I5", List.of("apps/web/src/shared/money/money.test.ts#test:I5")),

            // Two rules, because the spec's I6 is two bans: a property/method that would parse a float, and a
            // syntax that would divide by a hundred. Either one alone leaves the other half unenforced.
            Map.entry("I6", List.of(
                    "apps/web/eslint.config.js#rule:no-restricted-properties",
                    "apps/web/eslint.config.js#rule:no-restricted-syntax")));

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
            if (!labelled.contains(property)
                    && !ENFORCED_PROPERTIES.containsKey(property)
                    && !DEFERRED_PROPERTIES.containsKey(property)) {
                silent.add(property);
            }
        }

        assertThat(silent)
                .as("these properties are declared in docs/specs and nothing claims to enforce them, so "
                        + "nothing would notice if they stopped being true. Label the assertion "
                        + "`.as(\"<id>: ...\")`, or add the property to ENFORCED_PROPERTIES with the file "
                        + "and anchor that enforces it, or add it to DEFERRED_PROPERTIES with the reason "
                        + "it cannot be tested yet")
                .isEmpty();

        List<String> stale = DEFERRED_PROPERTIES.keySet().stream()
                .filter(property -> !defined.contains(property))
                .toList();
        assertThat(stale)
                .as("DEFERRED_PROPERTIES names properties that no spec declares any more")
                .isEmpty();

        // Enforcement counts here as well as labelling, and that widening is the point of ADR 0052: a
        // deferral that outlived its implementation is stale whichever way the implementation arrived, and
        // the i18n family was deferred for three slices while a Node script, a Vitest test and two lint rules
        // enforced every one of them. Checking only JVM labels is what let the two disagree in silence.
        List<String> noLongerTrue = DEFERRED_PROPERTIES.keySet().stream()
                .filter(property -> labelled.contains(property) || ENFORCED_PROPERTIES.containsKey(property))
                .toList();
        assertThat(noLongerTrue)
                .as("these properties are deferred but something does enforce them, so the deferral is "
                        + "stale and is understating what is covered")
                .isEmpty();
    }

    /**
     * The claims in {@link #ENFORCED_PROPERTIES} still point at something that declares that check.
     *
     * <p>This is the direction that makes the map worth having. A claim naming a file that has moved, a
     * Vitest test that was renamed, or an ESLint rule that was switched off is a sentence that reads like
     * coverage and is not - and nothing else in this build would say so, because the file it names belongs to
     * another language, another test runner and another toolchain, none of which this module runs. It is also
     * the check that would have caught the i18n family's deferral from the other end: it fails when the
     * evidence disappears, and the deferral failed when the evidence appeared.
     */
    @Test
    void everyEnforcementClaimOutsideTheJvmResolves() throws IOException {
        List<String> unresolved = new ArrayList<>();

        for (Map.Entry<String, List<String>> claim : ENFORCED_PROPERTIES.entrySet()) {
            for (String reference : claim.getValue()) {
                String reason = checkFileReference(reference);

                if (reason != null) {
                    unresolved.add(claim.getKey() + " -> " + reference + ": " + reason);
                }
            }
        }

        assertThat(ENFORCED_PROPERTIES.values().stream().mapToInt(List::size).sum())
                .as("ENFORCED_PROPERTIES is empty, so the check below would pass without checking "
                        + "anything - which is the failure mode this whole class exists to catch")
                .isGreaterThan(0);

        assertThat(unresolved)
                .as("a property is claimed as enforced by a file that does not declare that check, so the "
                        + "claim reads like coverage and is not")
                .isEmpty();
    }

    /**
     * No property is both enforced and deferred, and every claim names a property a spec declares.
     *
     * <p>{@link RuleCoverageTest} gained this direction when a rule turned out to be enforced and deferred at
     * once, and the reason is the same here: the two maps answer different questions - what covers this, and
     * what cannot be covered yet - so an entry in both makes the pair say nothing. A key that no spec defines
     * is the other half: a claim about a property that no longer exists under that id.
     */
    @Test
    void enforcementAndDeferralDoNotOverlap() throws IOException {
        Set<String> defined = propertiesDefinedInTheSpecs();

        assertThat(ENFORCED_PROPERTIES.keySet().stream()
                .filter(property -> !defined.contains(property))
                .toList())
                .as("ENFORCED_PROPERTIES claims enforcement for a property that no spec declares, so the "
                        + "claim points at an id that means nothing - usually a renumbering")
                .isEmpty();

        assertThat(ENFORCED_PROPERTIES.keySet().stream()
                .filter(DEFERRED_PROPERTIES::containsKey)
                .toList())
                .as("these properties are both enforced and deferred, so the two maps contradict each "
                        + "other: ENFORCED_PROPERTIES is the answer to 'what covers this' and "
                        + "DEFERRED_PROPERTIES is the answer to 'what cannot be covered yet'")
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

    /** The anchor kinds {@link #checkFileReference} knows, and the only spellings that resolve. */
    private static final String BANNER = "banner";

    private static final String TEST = "test";

    private static final String RULE = "rule";

    private static final String ANCHOR_KINDS = BANNER + ", " + TEST + " or " + RULE;

    /**
     * Resolves a claim about a check that lives outside this JVM, by reading the file it names.
     *
     * <p><strong>Why reading a file rather than running the check.</strong> This module runs none of them:
     * the catalog tool is Node, the property tests are Vitest, the lint rules are ESLint. Running them from
     * here would mean Gradle reaching into another toolchain to assert a cross-language wiring, where what
     * matters is the narrower claim that the check is still declared. Reading the source proves that much and
     * says so, and two other guards here already read files for the same reason - {@code SchemaOwnershipTest}
     * reads migrations and {@link RuleCoverageTest} reads a named test's source - because some facts are about
     * what a repository contains rather than about what a JVM can load.
     *
     * <p><strong>Each anchor is a declaration, and that is the difference between this and a substring
     * search.</strong> A banner heads the block that does the work, a test name is what the runner registers,
     * and a quoted key is what turns an ESLint rule on. Searching for the bare id instead would match this
     * file's own header listing the family, which is exactly how a check of this kind lies.
     *
     * @return null when the claim resolves, otherwise why it does not
     */
    private static String checkFileReference(String reference) throws IOException {
        int hash = reference.indexOf('#');

        if (hash < 0) {
            return "not a <path>#<kind>:<anchor> reference, so nothing was checked";
        }

        String path = reference.substring(0, hash);
        String anchor = reference.substring(hash + 1);
        int colon = anchor.indexOf(':');

        if (colon < 0) {
            return "no anchor kind: expected one of " + ANCHOR_KINDS;
        }

        String kind = anchor.substring(0, colon);
        String name = anchor.substring(colon + 1);

        Path source = Repository.root().resolve(path);
        if (!Files.isRegularFile(source)) {
            return "no file at " + path + ", so this claim points at something that is not there";
        }

        String text = Files.readString(source);

        return switch (kind) {
            // A word boundary, not a `contains`. The id must END at the marker: `--- I2` must not be satisfied
            // by `--- I2x`, which is what the sabotage in ADR 0052 found when a renamed banner still passed.
            case BANNER -> Pattern.compile("---\\s+" + Pattern.quote(name) + "\\b")
                    .matcher(text)
                    .find()
                    ? null
                    : "no '--- " + name + "' section marker in " + path + ", so the block this claim "
                            + "names is gone";
            case TEST -> Pattern.compile("\\b(?:it|test)\\(\\s*['\"]" + Pattern.quote(name))
                    .matcher(text)
                    .find()
                    ? null
                    : "no test declared with a name starting '" + name + "' in " + path + ", so nothing "
                            + "is registered under this property any more";
            case RULE -> text.contains("'" + name + "':")
                    ? null
                    : "the rule '" + name + "' is not turned on in " + path + ", so this claim names a ban "
                            + "that is not in force";
            default -> "unknown anchor kind '" + kind + "', so nothing checked this claim - the kinds are "
                    + ANCHOR_KINDS;
        };
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
     * {@code X7} is an fx property and the assertion that labels it lives in {@code libs/planning}.
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
