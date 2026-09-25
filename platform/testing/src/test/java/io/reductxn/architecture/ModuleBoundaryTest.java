package io.reductxn.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
            "io.reductxn.rails.."
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
                            "io.reductxn.rails..")
                    .because("RULE B1: kernel is the root of the module graph");

    @ArchTest
    static final ArchRule b2_ledger_depends_only_on_kernel =
            noClasses().that().resideInAPackage("io.reductxn.ledger..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.reductxn.netting..",
                            "io.reductxn.fx..",
                            "io.reductxn.rails..")
                    .because("RULE B2: ledger must not depend on netting, fx or rails");

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
                            "io.reductxn.settlement..")
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
    static final ArchRule b8_no_cycles_between_modules =
            slices().matching("io.reductxn.(*)..")
                    .should().beFreeOfCycles()
                    .because("RULE B8: a cycle in the module graph makes every boundary negotiable");
}
