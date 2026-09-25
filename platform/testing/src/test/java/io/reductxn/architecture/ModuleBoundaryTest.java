package io.reductxn.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The RULEs of docs/MASTER-PLAN.md section 5, as executable checks.
 *
 * <p>This is the mechanism that keeps "modular" from decaying into "in a different folder". Each
 * rule names the RULE id it enforces, so a failing build states which principle was broken rather
 * than only which class moved.
 *
 * <p>These tests cover the pure core. The {@code apps/api} internal rules (B12-B16) are added in
 * this module once {@code apps/api} exists, because ArchUnit needs the classes on the classpath.
 */
@AnalyzeClasses(packages = "io.reductxn", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /**
     * The pure core, as separate package identifiers.
     *
     * <p>Deliberately NOT one pattern with alternation. ArchUnit package identifiers support only
     * {@code *} and {@code ..}, not regular-expression groups. An alternation pattern matches
     * nothing at all, which turns the rule vacuous instead of failing loudly - the exact opposite
     * of what an architecture rule is for.
     */
    private static final String[] LIBS = {
            "io.reductxn.kernel..",
            "io.reductxn.ledger..",
            "io.reductxn.netting..",
            "io.reductxn.fx..",
            "io.reductxn.planning..",
            "io.reductxn.rails.."
    };

    /** The public surface of each pure module: everything else in it is reachable but not promised. */
    private static final String[] API_PACKAGES = {
            "io.reductxn.kernel.api..",
            "io.reductxn.ledger.api..",
            "io.reductxn.netting.api..",
            "io.reductxn.fx.api..",
            "io.reductxn.planning.api..",
            "io.reductxn.rails.api.."
    };

    @ArchTest
    static final ArchRule b5_libs_are_framework_free =
            noClasses().that().resideInAnyPackage(LIBS)
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("RULE B5 / P4: libs/* must stay framework-free so an auditor can run "
                            + "the domain core without a container");

    @ArchTest
    static final ArchRule b5_libs_do_not_depend_on_deployables =
            noClasses().that().resideInAnyPackage(LIBS)
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.api..")
                    .because("RULE B5: the pure core must never depend on a deployable");

    @ArchTest
    static final ArchRule b1_kernel_depends_on_no_other_module =
            noClasses().that().resideInAPackage("io.reductxn.kernel..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.ledger..",
                            "io.reductxn.netting..",
                            "io.reductxn.fx..",
                            "io.reductxn.planning..",
                            "io.reductxn.rails..")
                    .because("RULE B1: kernel is the root of the module graph");

    @ArchTest
    static final ArchRule b2_ledger_depends_only_on_kernel =
            noClasses().that().resideInAPackage("io.reductxn.ledger..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.netting..",
                            "io.reductxn.fx..",
                            "io.reductxn.planning..",
                            "io.reductxn.rails..")
                    .because("RULE B2: ledger must not depend on netting, fx, settlement or rails");

    /**
     * RULE B3, which was stated in MASTER-PLAN section 5.1 but never enforced until now.
     *
     * <p>Worth having, and not as box-ticking: ADR 0012's central claim is that the netting engine stays
     * unaware of FX because its properties assume one currency at a time. That claim is only as good as
     * the rule that stops the engine acquiring the awareness.
     */
    @ArchTest
    static final ArchRule b3_netting_depends_only_on_kernel_and_ledger =
            noClasses().that().resideInAPackage("io.reductxn.netting..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.fx..",
                            "io.reductxn.planning..",
                            "io.reductxn.rails..")
                    .because("RULE B3: netting depends only on kernel and ledger, so the engine cannot "
                            + "acquire currency awareness its per-currency proofs do not account for");

    @ArchTest
    static final ArchRule b4_rails_depend_only_on_kernel_and_ledger =
            noClasses().that().resideInAPackage("io.reductxn.rails..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.netting..",
                            "io.reductxn.fx..",
                            "io.reductxn.planning..")
                    .because("RULE B4: rails depend only on kernel and ledger");

    @ArchTest
    static final ArchRule b10_netting_internal_is_private =
            noClasses().that().resideOutsideOfPackage("io.reductxn.netting..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.netting.internal..")
                    .because("RULE B10: internal packages are reachable only through the api package's "
                            + "public factories");

    @ArchTest
    static final ArchRule b10_rails_internal_is_private =
            noClasses().that().resideOutsideOfPackage("io.reductxn.rails..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.rails.internal..")
                    .because("RULE B10: rail implementations are reached only through the port");

    /**
     * RULE B10 for ledger, which had no subject to check until {@code io.reductxn.ledger.internal}
     * existed.
     *
     * <p>Worth stating, because this rule reported green for as long as that package was empty: its
     * subject resolved, so {@code emptyShould} never fired, and it checked nothing. The entry hash
     * encoding is what activated it. Verified by sabotage - a call into the package from
     * {@code libs/netting} fails the rule, and that reference compiles, so the rule is the only thing
     * standing in the way.
     *
     * <p><strong>Found while doing that: a dependency rule cannot see an inlined constant.</strong> A
     * reference to a {@code public static final} compile-time constant leaves no trace in the bytecode,
     * because the compiler substitutes the value. A field reference passed this rule; a method call to
     * the same class failed it. That is a limit of checking compiled dependencies rather than a fault -
     * a copied constant creates no coupling - but it means B10 cannot keep outsiders out of a constant,
     * so {@code EntryHash.hexLength()} is a method. Recorded in MASTER-PLAN section 15.
     */
    @ArchTest
    static final ArchRule b10_ledger_internal_is_private =
            noClasses().that().resideOutsideOfPackage("io.reductxn.ledger..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.ledger.internal..")
                    .because("RULE B10: ledger internals are not another module's business");

    @ArchTest
    static final ArchRule b10_fx_internal_is_private =
            noClasses().that().resideOutsideOfPackage("io.reductxn.fx..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.fx.internal..")
                    .because("RULE B10: the conversion and its power-of-ten table are reached only "
                            + "through FxConverters and FxQuote");

    @ArchTest
    static final ArchRule b18_fx_depends_only_on_kernel_and_ledger =
            noClasses().that().resideInAPackage("io.reductxn.fx..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.netting..",
                            "io.reductxn.rails..",
                            "io.reductxn.api..",
                            "io.reductxn.planning..")
                    .because("RULE B18: fx may not depend on netting, so rate logic can never hide "
                            + "inside the engine whose proofs assume one currency at a time");

    /**
     * RULE B19, the half that is expressible today.
     *
     * <p>States that no other pure module may see FX at all. That is stronger than "do not multiply
     * by a rate" and easier to check: a class that cannot see {@code FxRate} cannot multiply by one.
     *
     * <p>The other half of B19 - that only {@code libs/fx} may read {@code fx_rates} and
     * {@code fx_quotes} - is a table-ownership rule and needs {@code apps/api} on the classpath
     * before it can be asserted. It is listed in MASTER-PLAN section 8 as a gate for that reason.
     */
    @ArchTest
    static final ArchRule b19_no_other_domain_module_knows_about_fx =
            noClasses().that().resideInAnyPackage(
                            "io.reductxn.kernel..",
                            "io.reductxn.ledger..",
                            "io.reductxn.netting..",
                            "io.reductxn.rails..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.fx..")
                    .because("RULE B19: conversion happens only in libs/fx");

    @ArchTest
    static final ArchRule b10_planning_internal_is_private =
            noClasses().that().resideOutsideOfPackage("io.reductxn.planning..")
                    .should().dependOnClassesThat().resideInAPackage("io.reductxn.planning.internal..")
                    .because("RULE B10: the partitioner is reached only through SettlementPlans");

    /**
     * RULE B11: an {@code internal} type never appears in an {@code api} signature.
     *
     * <p>Section 5.4 has claimed since it was written that "these two rules [B10 and B11] are enforced
     * by ArchUnit". B10 was. B11 was not, and went unnoticed for as long as nobody checked - which is
     * the exact failure mode these rules exist to prevent. It is a real gap rather than a technicality:
     * B10 stops another module <em>reaching into</em> internals, and B11 stops there being anything
     * there to reach for once a return type changes.
     *
     * <p>Note what is deliberately allowed: an {@code api} class may <em>depend on</em> an internal one
     * in order to construct it. {@code TaxRules}, {@code NettingEngines} and {@code FxConverters} do
     * exactly that, which is the sanctioned factory pattern - the public surface stays in the {@code api}
     * package while the implementation is hidden. A rule written as a plain dependency check would
     * forbid the pattern that makes B10 usable in the first place. Only <em>signatures</em> are checked.
     *
     * <p>Implemented as a custom condition over a <em>positive</em> rule rather than
     * {@code noClasses()}. The first attempt used {@code noClasses().should(customCondition)} and
     * passed against a deliberate sabotage - a return type changed to an internal one - because
     * negation does not compose the way it does for built-in conditions. A custom condition reports the
     * violations it finds, so it belongs on a positive rule where nothing has to be inverted. Verified
     * by breaking it: with this form, returning {@code GreedyNetting} from
     * {@code NettingEngines.greedy()} fails the rule.
     */
    @ArchTest
    static final ArchRule b11_api_never_exposes_an_internal_type =
            classes().that().resideInAnyPackage(API_PACKAGES)
                    .should(neverExposeAnInternalType())
                    .because("RULE B11: an internal type must never appear in a public signature");

    private static ArchCondition<JavaClass> neverExposeAnInternalType() {
        return new ArchCondition<>("never expose a type from an internal package in a signature") {

            @Override
            public void check(JavaClass apiClass, ConditionEvents events) {
                List<String> leaks = new ArrayList<>();

                for (JavaMethod method : apiClass.getMethods()) {
                    if (method.getOwner().equals(apiClass)) {
                        collectLeaks(leaks, method.getFullName(), method.getRawReturnType(),
                                method.getRawParameterTypes(), method.getThrowsClause().getTypes());
                    }
                }

                for (JavaConstructor constructor : apiClass.getConstructors()) {
                    if (constructor.getOwner().equals(apiClass)) {
                        collectLeaks(leaks, apiClass.getSimpleName() + "(..)", null,
                                constructor.getRawParameterTypes(), List.of());
                    }
                }

                for (JavaField field : apiClass.getFields()) {
                    if (field.getOwner().equals(apiClass) && isInternal(field.getRawType())) {
                        leaks.add(field.getFullName() + " is of internal type "
                                + field.getRawType().getName());
                    }
                }

                leaks.forEach(leak -> events.add(SimpleConditionEvent.violated(apiClass, leak)));
            }
        };
    }

    private static void collectLeaks(
            List<String> leaks,
            String where,
            JavaClass returnType,
            List<JavaClass> parameterTypes,
            List<JavaClass> thrownTypes) {

        if (returnType != null && isInternal(returnType)) {
            leaks.add(where + " returns internal type " + returnType.getName());
        }
        for (JavaClass parameterType : parameterTypes) {
            if (isInternal(parameterType)) {
                leaks.add(where + " accepts internal type " + parameterType.getName());
            }
        }
        for (JavaClass thrownType : thrownTypes) {
            if (isInternal(thrownType)) {
                leaks.add(where + " declares internal exception " + thrownType.getName());
            }
        }
    }

    /**
     * Whether the type lives in a package with a segment named exactly {@code internal}.
     *
     * <p>Checked segment by segment rather than with a substring test, so a package called
     * {@code internalish} or {@code internals} is not mistaken for an internal package.
     */
    private static boolean isInternal(JavaClass type) {
        return Arrays.asList(type.getPackageName().split("\\.")).contains("internal");
    }

    /**
     * RULE B21: the new module's own boundary.
     *
     * <p>It may not reach sideways into a deployable, nor into rails: it composes a plan, it does not
     * execute one.
     *
     * <p><strong>Two of these four exclusions cannot fire yet, and saying so is the point.</strong>
     * {@code apps/api} and {@code workers/settlement} have no sources, so ArchUnit has no classes to
     * match against and a violation would go unnoticed even though the rule is not vacuous - its
     * subject resolves, so it is genuinely evaluated. The {@code rails} exclusion is live today, since
     * that module does have classes. The remaining two become real when the deployables do, and they
     * are listed now so the boundary is stated before anything can cross it.
     */
    @ArchTest
    static final ArchRule b21_planning_depends_only_on_kernel_ledger_netting_and_fx =
            noClasses().that().resideInAPackage("io.reductxn.planning..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.rails..",
                            "io.reductxn.api..",
                            "io.reductxn.worker..",
                            "io.reductxn.platform..")
                    .because("RULE B21: settlement owns what must be settled, not how it moves");

    @ArchTest
    static final ArchRule b8_no_cycles_between_modules =
            slices().matching("io.reductxn.(*)..")
                    .should().beFreeOfCycles()
                    .because("RULE B8: a cycle in the module graph makes every boundary negotiable");
}
