package io.reductxn.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.reductxn.kernel.api.DomainCodes;
import io.reductxn.kernel.api.DomainError;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds every {@link DomainError} to the code convention in {@link DomainCodes}.
 *
 * <p>This exists because the convention was already broken once and nothing noticed. {@code libs/fx}
 * returned dotted, namespaced codes while {@code libs/ledger} returned a bare {@code CURRENCY_MISMATCH},
 * and the only record of the disagreement was a comment in {@code FxException} calling it a follow-up.
 * A convention with no check is a comment.
 *
 * <p>Four things are enforced, and each has a reason beyond tidiness:
 *
 * <ul>
 *   <li><strong>Well formed.</strong> A code is contract surface (P5, RULE B20) and a client branches
 *       on it, so its shape cannot drift per module.</li>
 *   <li><strong>Namespaced by the owning module.</strong> This is what makes a collision impossible
 *       rather than merely unlikely, and what makes a code attributable when it appears in a log.</li>
 *   <li><strong>Unique.</strong> Two classes sharing a code gives the client catalog one key and two
 *       meanings - a wrong message reaching a user rather than a build failure.</li>
 *   <li><strong>Declared as a constant.</strong> The error-code parity gate (I3) has to enumerate every
 *       code the server can emit in order to check it against {@code en/errors.json}. Codes hidden
 *       inside method bodies cannot be enumerated, so they cannot be gated. This is the piece that
 *       makes that gate possible rather than aspirational.</li>
 * </ul>
 *
 * <p><strong>What this does not prove.</strong> It reads the {@code CODE} constant, not the value
 * {@code code()} returns at runtime. A class that declared one code and passed a different, still
 * well-formed literal to its constructor would pass here. That is a deliberate gap rather than an
 * oversight: closing it needs instantiation, every exception has a different constructor, and
 * {@code FxException} already validates whatever code it is handed, so the remaining hole needs
 * someone to actively pass a second well-formed code. Stated because a test that looks stronger than
 * it is, is worse than one that says what it covers.
 */
class DomainErrorCodeTest {

    private static final String ROOT_PACKAGE = "io.reductxn";

    /**
     * Modules known to declare a code today.
     *
     * <p>Asserted so that losing sight of a module fails loudly. The recurring bug in this repository
     * is not a wrong rule but an invisible one - a rule whose subject was never imported, which reports
     * green while checking nothing. If a dependency is dropped from {@code platform/testing}, the
     * enumeration below quietly shrinks and every assertion still passes.
     *
     * <p>{@code rails} joined the list when the port grew its refusal classification: before that, the
     * only rail refusal in the repository lived in a worker's test sources, which this test cannot see -
     * so the two codes a rail adapter needs to speak were convention-checked by nobody.
     */
    private static final Set<String> MODULES_THAT_DECLARE_CODES =
            Set.of("fx", "ledger", "rails", "api", "settlement");

    @Test
    void everyDomainErrorHasAConventionAbidingUniqueCode() {
        List<JavaClass> implementations = concreteDomainErrors();

        assertThat(implementations)
                .as("no concrete DomainError was imported, so this test checks nothing")
                .isNotEmpty();

        // Sorted, so a failure report reads the same on every machine.
        Map<String, String> ownerOfCode = new TreeMap<>();
        List<String> problems = new ArrayList<>();

        for (JavaClass implementation : implementations) {
            String owner = implementation.getName();
            String declared = declaredCodeConstant(implementation);

            if (declared == null) {
                problems.add(owner + " implements DomainError but does not declare a public "
                        + "static final String CODE, so its code cannot be checked, referenced, or "
                        + "enumerated for the parity gate");
                continue;
            }

            if (!isWellFormed(declared)) {
                problems.add(owner + " declares the malformed code '" + declared
                        + "'. See DomainCodes for the convention");
                continue;
            }

            String expectedNamespace = moduleOf(implementation.getPackageName());
            String actualNamespace = DomainCodes.namespaceOf(declared);
            if (!actualNamespace.equals(expectedNamespace)) {
                problems.add(owner + " declares code '" + declared + "', whose namespace is '"
                        + actualNamespace + "', but the class lives in module '" + expectedNamespace
                        + "'. A code must be attributable to the module that owns it");
            }

            String previousOwner = ownerOfCode.putIfAbsent(declared, owner);
            if (previousOwner != null) {
                problems.add("the code '" + declared + "' is declared by both " + previousOwner
                        + " and " + owner + ", so one client catalog key would carry two meanings");
            }
        }

        assertThat(problems).as("error codes break the DomainCodes convention").isEmpty();

        Set<String> namespaces = new TreeSet<>();
        ownerOfCode.keySet().forEach(code -> namespaces.add(DomainCodes.namespaceOf(code)));

        assertThat(namespaces)
                .as("the set of modules declaring codes changed. Most likely platform/testing lost a "
                        + "dependency, which makes this test blind to that module rather than failing")
                .containsExactlyInAnyOrderElementsOf(MODULES_THAT_DECLARE_CODES);
    }

    private static List<JavaClass> concreteDomainErrors() {
        JavaClasses imported = new ClassFileImporter().importPackages(ROOT_PACKAGE);

        return imported.that(assignableTo(DomainError.class)).stream()
                .filter(javaClass -> !javaClass.isInterface())
                // ArchUnit reports modifiers as a set of JavaModifier, not as a java.lang.reflect int.
                .filter(javaClass -> !javaClass.getModifiers().contains(JavaModifier.ABSTRACT))
                .sorted(Comparator.comparing(JavaClass::getName))
                .toList();
    }

    /** The class's own {@code CODE} constant, or null if it does not declare a usable one. */
    private static String declaredCodeConstant(JavaClass implementation) {
        try {
            Field code = implementation.reflect().getDeclaredField("CODE");
            if (!Modifier.isStatic(code.getModifiers())
                    || !Modifier.isPublic(code.getModifiers())
                    || !String.class.equals(code.getType())) {
                return null;
            }
            return (String) code.get(null);
        } catch (NoSuchFieldException | IllegalAccessException notUsable) {
            return null;
        }
    }

    private static boolean isWellFormed(String code) {
        try {
            DomainCodes.requireWellFormed(code);
            return true;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** The module segment of a package: {@code io.reductxn.ledger.api} is {@code ledger}. */
    private static String moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT_PACKAGE + ".")) {
            return packageName;
        }
        String rest = packageName.substring(ROOT_PACKAGE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
