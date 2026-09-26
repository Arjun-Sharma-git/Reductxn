package io.reductxn.architecture;

import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 *   <li>Every {@code RULE Xn} in {@code docs/MASTER-PLAN.md} <em>or</em> {@code docs/specs/*.md} must
 *       appear in {@link #ENFORCED_RULES}, {@link #DEFERRED_RULES}, or a family in
 *       {@link #OUT_OF_SCOPE_FAMILIES}. A newly declared rule with no decision fails the build.</li>
 *   <li>Nothing may appear in those maps that the documents no longer declare, because a stale entry
 *       reads as coverage.</li>
 *   <li><strong>Each "enforced by" reference must resolve.</strong> An {@code ArchTest} field must exist
 *       <em>and carry the annotation</em>, so a rule that exists but is never evaluated is caught; a
 *       Gradle guard must be declared in {@code build-logic}; and a test in another module is resolved by
 *       reading its source for the {@code @Test} method named (see {@link #checkSourceReference} - the
 *       module's tests are not on this module's classpath, and a behavioural rule that could not be
 *       claimed at all was being filed as deferred instead). This is the part that would have caught B11
 *       on the day it was written.</li>
 * </ol>
 *
 * <p><strong>Reading the specs matters as much as reading the plan.</strong> The first version of this
 * test scanned {@code MASTER-PLAN.md} alone and reported full coverage while {@code S1-S5} - declared
 * as {@code RULE S1} in {@code docs/specs/settlement.md}, and the whole quorum, idempotency, partial
 * application and FX funding story - had no decision at all. Five rules invisible for the same reason a
 * rule whose subject is never imported is invisible: the check looked somewhere they were not.
 *
 * <p>Scope is the RULEs. The property families in {@code docs/specs/*} - netting N1-N9, fx X1-X7,
 * ledger L1-L9, i18n I1-I7 - are a different kind of claim, about behaviour rather than structure, and
 * they are covered by {@link PropertyCoverageTest} rather than here.
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
            Map.entry("B6", List.of(
                    "ModuleBoundaryTest#b6_api_never_depends_on_the_worker",
                    "ModuleBoundaryTest#b6_worker_never_depends_on_the_api")),
            Map.entry("B8", List.of("ModuleBoundaryTest#b8_no_cycles_between_modules")),
            Map.entry("B10", List.of(
                    "ModuleBoundaryTest#b10_ledger_internal_is_private",
                    "ModuleBoundaryTest#b10_netting_internal_is_private",
                    "ModuleBoundaryTest#b10_fx_internal_is_private",
                    "ModuleBoundaryTest#b10_planning_internal_is_private",
                    "ModuleBoundaryTest#b10_rails_internal_is_private")),
            Map.entry("B11", List.of("ModuleBoundaryTest#b11_api_never_exposes_an_internal_type")),
            Map.entry("B12", List.of(
                    "ModuleBoundaryTest#b12_web_never_touches_adapters",
                    "ModuleBoundaryTest#b12_web_reaches_application_only_through_port_in")),
            Map.entry("B13", List.of(
                    "ModuleBoundaryTest#b13_application_calls_ports_not_implementations")),
            Map.entry("B14", List.of(
                    "ModuleBoundaryTest#b14_adapters_never_touch_application_or_web")),
            Map.entry("B15", List.of("ModuleBoundaryTest#b15_transactional_only_in_application")),
            Map.entry("B16", List.of(
                    "ModuleBoundaryTest#b16_row_types_never_leave_the_persistence_adapters")),
            Map.entry("B17", List.of(
                    "SchemaOwnershipTest#everyTableIsCreatedByExactlyOneModule",
                    "SchemaOwnershipTest#everyMigrationVersionIsInItsOwnersRange")),
            Map.entry("B22", List.of(
                    "ModuleBoundaryTest#b22_booking_depends_only_on_kernel_ledger_planning_and_fx")),
            Map.entry("B20", List.of("ModuleBoundaryTest#b20_api_has_no_way_to_localise")),
            Map.entry("B18", List.of("ModuleBoundaryTest#b18_fx_depends_only_on_kernel_and_ledger")),
            Map.entry("B19", List.of("ModuleBoundaryTest#b19_no_other_domain_module_knows_about_fx")),
            Map.entry("B21", List.of(
                    "ModuleBoundaryTest#b21_planning_depends_only_on_kernel_ledger_netting_and_fx")),
            // The settlement lifecycle rules (RULE S1-S5). Their enforcement is a test in another module, which is
            // a form this map could not previously name at all - see checkSourceReference for why, and why reading
            // the source is the only honest way to resolve one.
            Map.entry("S1", List.of(
                    "io.reductxn.settlement.QuorumTest#everyDebtorMustConfirmPersonally",
                    "io.reductxn.settlement.ExecuteRunTest#aRunWithoutQuorumDoesNotExecute")),
            Map.entry("S2", List.of(
                    "io.reductxn.settlement.ExecuteRunTest#replayingAnExecutedRunAsksTheRailForNothingNew",
                    "io.reductxn.rails.api.MockRailTest#resubmittingReturnsWhatItAlreadyHasRatherThanMovingAgain")),
            Map.entry("S3", List.of(
                    "io.reductxn.settlement.ExecuteRunTest#aRefusedTransferLeavesTheRunFailedAndTheOthersConfirmed")),
            Map.entry("S4", List.of(
                    "io.reductxn.settlement.ExecuteRunTest#aRunBlockedOnFundingDoesNotExecute",
                    "io.reductxn.settlement.RecordFxFundingTest#aCrossCurrencyRunStaysBlockedUntilEveryCurrencyIsCovered")),
            Map.entry("S5", List.of(
                    "io.reductxn.settlement.ComputeRunTest#aCrossCurrencyCycleWaitsForFundingAndReportsTheRequirementPerCurrency")),
            // A principle that also has a mechanical guard: money is an integer minor unit, and no
            // floating-point type may appear in domain code.
            Map.entry("P1", List.of("build-logic#forbidFloatingPointMoney")),
            Map.entry("N1", List.of("build-logic#forbidWildcardImports")));

    /** Rules known to be unenforced, with the reason, so a gap is a decision rather than a secret. */
    private static final Map<String, String> DEFERRED_RULES = Map.ofEntries(
            Map.entry("B7", "platform/* may not depend on a deployable. platform/testing is the only "
                    + "platform module with code and it lives in io.reductxn.architecture, so there is "
                    + "no platform package pattern to express this over yet"),
            Map.entry("B9", "compiler settings live only in build-logic. They do, applied by the "
                    + "convention plugin - but nothing detects a module that hand-rolls its own. That "
                    + "check would have to read build files, a different guard from every other here"),
            // B12-B17 and B20 have all left this map: each one needed something that now exists - an API
            // module, a persistence adapter, a schema. What remains are the rules that need something
            // bigger than a module, and each names the missing mechanism rather than the missing work.
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
    void everyRuleDeclaredInTheDocsHasADecision() throws IOException {
        Set<String> declared = rulesDeclaredInTheDocs();

        assertThat(declared)
                .as("no RULE was found in docs/, so this test checks nothing - either the documents "
                        + "moved or they stopped writing rule ids as 'RULE X'")
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
    void noRuleIsBothEnforcedAndDeferred() {
        Set<String> both = new TreeSet<>(ENFORCED_RULES.keySet());
        both.retainAll(DEFERRED_RULES.keySet());

        assertThat(both)
                .as("a rule cannot be both enforced and deferred. This direction is checked because the "
                        + "other one is: PropertyCoverageTest fails when a property's deferral goes "
                        + "stale, and this map had no equivalent, so moving a rule from DEFERRED_RULES "
                        + "to ENFORCED_RULES could be done halfway and stay green")
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

    /**
     * Resolves a reference to a test in another module by reading the source it names.
     *
     * <p><strong>Why reading a file rather than reflection.</strong> {@code platform/testing} sees every module's
     * <em>published</em> classes and none of their tests, so {@code Class.forName} cannot reach
     * {@code ExecuteRunTest} however the reference is written. The alternatives were to claim such a rule as
     * deferred while it is enforced - which is what this map did for S1-S5, and is a lie in the direction that
     * reads better - or to check the source. {@code SchemaOwnershipTest} already reads migration files for the same
     * reason: some facts are about what a repository contains rather than about what a JVM can load.
     *
     * <p><strong>What this proves and what it does not.</strong> It proves that a test method with that name
     * exists, carries {@code @Test}, and lives in the class the reference names - so a rule cannot be claimed as
     * enforced by naming something that does not run. It cannot prove the test asserts what the rule says; nothing
     * mechanical can, and the claim is a written decision either way.
     */
    private static String checkSourceReference(String className, String member) throws IOException {
        String relative = className.replace('.', '/') + ".java";
        Path source = null;

        for (String container : List.of("libs", "apps", "workers", "platform")) {
            Path containerRoot = repositoryRoot().resolve(container);
            if (!Files.isDirectory(containerRoot)) {
                continue;
            }

            try (var modules = Files.list(containerRoot)) {
                for (Path module : modules.filter(Files::isDirectory).toList()) {
                    Path candidate = module.resolve("src/test/java").resolve(relative);
                    if (Files.isRegularFile(candidate)) {
                        source = candidate;
                        break;
                    }
                }
            }

            if (source != null) {
                break;
            }
        }

        if (source == null) {
            return "no test source for " + className + " exists under any module's src/test/java, so this claim "
                    + "points at nothing";
        }

        String text = Files.readString(source);
        Pattern method = Pattern.compile(
                "@Test\\b[\\s\\S]{0,300}?\\bvoid\\s+" + Pattern.quote(member) + "\\s*\\(");

        if (!method.matcher(text).find()) {
            return text.contains(member + "(")
                    ? "the source of " + className + " has a method named '" + member + "' with no @Test, so "
                            + "nothing runs it"
                    : "the source of " + className + " has no test method named '" + member + "', so this claim "
                            + "is stale";
        }

        return null;
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

        // A reference to a test in another module, named by its fully-qualified class name. It cannot be resolved
        // by reflection: a module's test sources are not on this module's classpath, which is precisely the
        // boundary that made a behavioural rule's enforcement unclaimable here until now - S1 to S5 are enforced
        // by tests in workers/settlement, and this map could not name one.
        if (parts[0].contains(".")) {
            return checkSourceReference(parts[0], parts[1]);
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
        } catch (NoSuchFieldException notARuleField) {
            return methodOrStale(parts);
        }
    }

    /**
     * The other kind of enforcement: a plain JUnit test.
     *
     * <p>B17 cannot be an ArchUnit rule. It reads migration files rather than classes, and "each table
     * has one owner" is a fact about SQL rather than about bytecode. The map has to be able to point at
     * that kind of check, or the honest option becomes writing a weaker rule that fits the checker.
     */
    private static String methodOrStale(String[] parts) {
        try {
            Method method = Class.forName(RuleCoverageTest.class.getPackageName() + "." + parts[0])
                    .getDeclaredMethod(parts[1]);

            return method.isAnnotationPresent(Test.class)
                    ? null
                    : "a member named '" + parts[1] + "' exists but has neither @ArchTest nor @Test, "
                            + "so nothing runs it";
        } catch (ClassNotFoundException missingClass) {
            return "no class named " + parts[0] + " exists in this package";
        } catch (NoSuchMethodException missingMethod) {
            return "no rule field and no test method named '" + parts[1] + "' exists, so this claim "
                    + "is stale";
        }
    }
    private static Set<String> rulesDeclaredInTheDocs() throws IOException {
        Set<String> declared = new TreeSet<>();

        collectRuleIds(repositoryRoot().resolve("docs/MASTER-PLAN.md"), declared);

        // Specs as well as the plan. S1-S5 live only in docs/specs/settlement.md, and reading the plan
        // alone made this test report full coverage while five declared rules had no decision at all -
        // the same shape of blindness as a rule whose subject was never imported.
        try (var specs = Files.list(repositoryRoot().resolve("docs/specs"))) {
            for (Path spec : specs.filter(path -> path.toString().endsWith(".md")).toList()) {
                collectRuleIds(spec, declared);
            }
        }
        return declared;
    }

    private static void collectRuleIds(Path document, Set<String> into) throws IOException {
        Matcher matcher = Pattern.compile("\\bRULE\\s+([A-Z]+\\d+)\\b")
                .matcher(Files.readString(document));
        while (matcher.find()) {
            into.add(matcher.group(1));
        }
    }

    private static String familyOf(String rule) {
        return rule.substring(0, 1);
    }

    /**
     * The repository root, shared with the other coverage tests.
     */
    private static Path repositoryRoot() {
        return Repository.root();
    }
}
