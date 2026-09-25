package io.reductxn.architecture;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes every RULE's enforcement status explicit, and verifies the claims it makes.
 *
 * <p>This exists because the recurring failure in this repository is not a rule being broken. It is a
 * rule being <em>unenforced while everyone believes otherwise</em>:
 *
 * <ul>
 *   <li><strong>B3 and B4</strong> were stated in MASTER-PLAN section 5.1 for the life of the project
 *       and asserted nowhere. Eight rules were written for a twelve-rule graph.</li>
 *   <li><strong>B11</strong> was described in section 5.4 as "enforced by ArchUnit" from the day it was
 *       written. It did not exist.</li>
 *   <li><strong>RULE N1</strong> was recorded as "not yet gated in CI" in Appendix A, and stayed that
 *       way.</li>
 * </ul>
 *
 * <p>A rule that is unenforced is indistinguishable from a rule that is obeyed, which is why each of
 * those survived so long. So this test requires a decision per rule, and then checks the decision:
 *
 * <ol>
 *   <li>Every {@code RULE Xn} in {@code docs/MASTER-PLAN.md} must appear in {@link #ENFORCED_RULES},
 *       {@link #DEFERRED_RULES}, or a family in {@link #OUT_OF_SCOPE_FAMILIES}. A newly declared rule
 *       with no decision fails the build.</li>
 *   <li>Nothing may appear in those maps that the plan no longer declares, because a stale entry reads
 *       as coverage.</li>
 *   <li><strong>Each "enforced by" reference must resolve.</strong> An {@code ArchTest} field must exist
 *       <em>and carry the annotation</em>, so a rule that exists but is never evaluated is caught; a
 *       Gradle guard must be declared in {@code build-logic}. This is the part that would have caught
 *       B11 on the day it was written.</li>
 * </ol>
 *
 * <p>Scope is the RULEs declared in the plan. The properties in {@code docs/specs/*} (N1-N9, X1-X7,
 * L1-L9, S1-S5) are a different kind of claim, checked by jqwik and golden fixtures beside the code
 * they govern, and each spec already carries its own coverage table.
 */
class RuleCoverageTest {

    /**
     * Rules that something in this build actually enforces.
     *
     * <p>Values are {@code Type#member} references, resolved by {@link #checkReference}. References
     * rather than prose specifically so they can be checked: a claim of enforcement that nothing
     * verifies is the bug this file was written for.
     */
    private static final Map<String, List<String>> ENFORCED_RULES = Map.ofEntries(
            Map.entry("B1", List.of("ModuleBoundaryTest#b1_kernel_depends_on_no_other_module")),
            Map.entry("B2", List.of("ModuleBoundaryTest#b2_ledger_depends_only_on_kernel")),
            Map.entry("B3", List.of("ModuleBoundaryTest#b3_netting_depends_only_on_kernel_and_ledger")),
            Map.entry("B4", List.of("ModuleBoundaryTest#b4_rails_depend_only_on_kernel_and_ledger")),
            Map.entry("B5", List.of(
                    "ModuleBoundaryTest#b5_libs_are_framework_free",
                    "ModuleBoundaryTest#b5_libs_do_not_depend_on_deployables")),
            Map.entry("B8", List.of("ModuleBoundaryTest#b8_no_cycles_between_modules")),
            Map.entry("B10", List.of(
                    "ModuleBoundaryTest#b10_ledger_internal_is_private",
                    "ModuleBoundaryTest#b10_netting_internal_is_private",
                    "ModuleBoundaryTest#b10_fx_internal_is_private",
                    "ModuleBoundaryTest#b10_settlement_internal_is_private",
                    "ModuleBoundaryTest#b10_rails_internal_is_private")),
            Map.entry("B11", List.of("ModuleBoundaryTest#b11_api_never_exposes_an_internal_type")),
            Map.entry("B18", List.of("ModuleBoundaryTest#b18_fx_depends_only_on_kernel_and_ledger")),
            Map.entry("B19", List.of("ModuleBoundaryTest#b19_no_other_domain_module_knows_about_fx")),
            Map.entry("B21", List.of(
                    "ModuleBoundaryTest#b21_settlement_depends_only_on_kernel_ledger_netting_and_fx")),
            // A principle that also has a mechanical guard: money is an integer minor unit, and no
            // floating-point type may appear in domain code.
            Map.entry("P1", List.of("build-logic#forbidFloatingPointMoney")),
            Map.entry("N1", List.of("build-logic#forbidWildcardImports")));

    /** Rules known to be unenforced, with the reason, so a gap is a decision rather than a secret. */
    private static final Map<String, String> DEFERRED_RULES = Map.ofEntries(
            Map.entry("B6", "apps/api and workers/settlement may not depend on each other. Neither has "
                    + "sources, so ArchUnit has no classes to match and the rule would be inert"),
            Map.entry("B7", "platform/* may not depend on a deployable. platform/testing is the only "
                    + "platform module with code and it lives in io.reductxn.architecture, so there is "
                    + "no platform package pattern to express this over yet"),
            Map.entry("B9", "compiler settings live only in build-logic. They do, applied by the "
                    + "convention plugin - but nothing detects a module that hand-rolls its own. That "
                    + "check would have to read build files, a different guard from every other here"),
            Map.entry("B12", "apps/api structure: web calls application only. Needs apps/api sources"),
            Map.entry("B13", "apps/api structure: application calls port/out only. Needs apps/api sources"),
            Map.entry("B14", "apps/api structure: adapter never calls application or web. Needs sources"),
            Map.entry("B15", "no @Transactional outside application. Needs apps/api sources"),
            Map.entry("B16", "no jOOQ row types out of adapter/persistence. Needs apps/api sources"),
            Map.entry("B17", "one owning module per table. Needs a schema and a persistence module"),
            Map.entry("B20", "the API never returns localized prose. Needs apps/api and its HTTP layer; "
                    + "the assertion belongs beside the problem-response mapping, not in this module"),
            Map.entry("R1", "the fixed top-level layout. Enforceable and deliberately not enforced: the "
                    + "check would fail the build for adding a directory, and an ADR is already the "
                    + "documented barrier. Judged to be friction without a matching benefit"));

    /**
     * Families whose gates are not this build's to run.
     *
     * <p>Kept to a family letter and one reason rather than listing each rule, because a single reason
     * covers the family. Twelve entries for F1-F12 would imply twelve decisions were made.
     */
    private static final Map<String, String> OUT_OF_SCOPE_FAMILIES = Map.of(
            "C", "contract and codegen rules (C1-C4). Their gate is a codegen diff check in CI "
                    + "(MASTER-PLAN section 8), not an ArchUnit rule and not a test in this module",
            "F", "frontend rules (F1-F12). There is no apps/web yet; Appendix B records it as unstarted");

    private static final String BUILD_LOGIC = "build-logic";

    private static final String BUILD_LOGIC_CONVENTIONS =
            "build-logic/src/main/kotlin/reductxn.java-conventions.gradle.kts";

    @Test
    void everyRuleDeclaredInThePlanHasADecision() throws IOException {
        Set<String> declared = rulesDeclaredInThePlan();

        assertThat(declared)
                .as("no RULE was found in docs/MASTER-PLAN.md, so this test checks nothing - either "
                        + "the plan moved or it stopped writing rule ids as 'RULE X'")
                .isNotEmpty();

        List<String> undecided = new ArrayList<>();
        for (String rule : declared) {
            if (ENFORCED_RULES.containsKey(rule) || DEFERRED_RULES.containsKey(rule)) {
                continue;
            }
            if (OUT_OF_SCOPE_FAMILIES.containsKey(familyOf(rule))) {
                continue;
            }
            undecided.add(rule);
        }

        assertThat(undecided)
                .as("these RULEs are declared in docs/MASTER-PLAN.md and nothing in this build either "
                        + "enforces or explicitly defers them. Add each to ENFORCED_RULES or "
                        + "DEFERRED_RULES. An unenforced rule is indistinguishable from an obeyed one, "
                        + "which is precisely how B3, B4 and B11 went unnoticed")
                .isEmpty();

        Set<String> decided = new TreeSet<>(ENFORCED_RULES.keySet());
        decided.addAll(DEFERRED_RULES.keySet());

        List<String> stale = decided.stream().filter(rule -> !declared.contains(rule)).toList();
        assertThat(stale)
                .as("this test claims coverage for rules docs/MASTER-PLAN.md no longer declares. A "
                        + "stale entry reads as coverage, so either remove it or restore the rule")
                .isEmpty();
    }

    @Test
    void everyRuleClaimedAsEnforcedResolvesToSomethingThatRuns() throws IOException {
        Map<String, String> unresolved = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> enforced : ENFORCED_RULES.entrySet()) {
            for (String reference : enforced.getValue()) {
                String complaint = checkReference(reference);
                if (complaint != null) {
                    unresolved.put(enforced.getKey() + " -> " + reference, complaint);
                }
            }
        }

        assertThat(unresolved)
                .as("a rule is claimed as enforced by something that does not exist, or that would "
                        + "never run. This is the check that would have caught B11 on the day it was "
                        + "described as enforced")
                .isEmpty();
    }

    /** Null when the reference resolves, otherwise the reason it does not. */
    private static String checkReference(String reference) throws IOException {
        String[] parts = reference.split("#", 2);
        if (parts.length != 2) {
            return "not a Type#member reference";
        }

        if (parts[0].equals(BUILD_LOGIC)) {
            String conventions = Files.readString(repositoryRoot().resolve(BUILD_LOGIC_CONVENTIONS));
            return conventions.contains("val " + parts[1])
                    ? null
                    : "no verification task named '" + parts[1] + "' is declared in build-logic";
        }

        try {
            Field field = Class.forName(RuleCoverageTest.class.getPackageName() + "." + parts[0])
                    .getDeclaredField(parts[1]);

            if (!ArchRule.class.isAssignableFrom(field.getType())) {
                return "the member exists but is not an ArchRule";
            }
            // A rule that exists but carries no annotation is never evaluated. That is the quiet form
            // of this whole failure mode, and the reason fields are resolved rather than just named.
            if (!field.isAnnotationPresent(ArchTest.class)) {
                return "the ArchRule exists but has no @ArchTest annotation, so ArchUnit never runs it";
            }
            return null;
        } catch (ClassNotFoundException missingClass) {
            return "no class named " + parts[0] + " exists in this package";
        } catch (NoSuchFieldException missingField) {
            return "no rule field named " + parts[1] + " exists, so this claim is stale";
        }
    }

    private static Set<String> rulesDeclaredInThePlan() throws IOException {
        String plan = Files.readString(repositoryRoot().resolve("docs/MASTER-PLAN.md"));

        Matcher matcher = Pattern.compile("\\bRULE\\s+([A-Z]+\\d+)\\b").matcher(plan);
        Set<String> declared = new TreeSet<>();
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    private static String familyOf(String rule) {
        return rule.substring(0, 1);
    }

    /**
     * The repository root, found by searching upwards for the build settings.
     *
     * <p>Searching rather than assuming {@code ../..} so the test keeps working if the module moves,
     * and so a failure says where it looked from rather than throwing a null path.
     */
    private static Path repositoryRoot() {
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
                        + ". This test reads docs/MASTER-PLAN.md and needs to run from inside the "
                        + "repository");
    }
}
