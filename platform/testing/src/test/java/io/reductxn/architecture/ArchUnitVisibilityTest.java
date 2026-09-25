package io.reductxn.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against architecture rules that silently check nothing.
 *
 * <p>An ArchUnit rule whose {@code that()} clause matches no imported classes either fails with
 * {@code emptyShould} or, worse, if {@code failOnEmptyShould} is ever relaxed, passes while enforcing
 * nothing at all. Both have already happened in this repository once.
 *
 * <p>There is a second, quieter form of the same problem, and this test exists because of it: a rule
 * can have a non-empty subject and still be vacuous because the <em>target</em> of its {@code should}
 * clause was never imported. {@code b19_no_other_domain_module_knows_about_fx} asserted that no other
 * module depends on {@code io.reductxn.fx}, while {@code io.reductxn.fx} was absent from this
 * module's dependencies. ArchUnit could not see a single FX class, so nothing could appear to depend
 * on one, and the rule reported green while proving nothing.
 *
 * <p>The fix is a dependency, and this test is what makes a future omission loud instead of silent.
 */
class ArchUnitVisibilityTest {

    /** Every module the architecture rules in {@link ModuleBoundaryTest} make claims about. */
    private static final List<String> RULE_SUBJECTS =
            List.of("kernel", "ledger", "netting", "fx", "planning", "rails", "api");

    @Test
    void archUnitCanSeeEveryModuleTheRulesTalkAbout() {
        JavaClasses imported = new ClassFileImporter().importPackages("io.reductxn");

        assertThat(imported).as("ArchUnit imported no classes at all").isNotEmpty();

        for (String module : RULE_SUBJECTS) {
            String packageName = "io.reductxn." + module;
            long visible = imported.stream()
                    .filter(javaClass -> javaClass.getPackageName().equals(packageName)
                            || javaClass.getPackageName().startsWith(packageName + "."))
                    .count();

            assertThat(visible)
                    .as("ArchUnit cannot see %s, so every rule mentioning it passes without "
                            + "checking anything. Add the module that owns %s to "
                            + "platform/testing/build.gradle.kts as a testImplementation dependency.",
                            packageName, packageName)
                    .isPositive();
        }
    }
}
