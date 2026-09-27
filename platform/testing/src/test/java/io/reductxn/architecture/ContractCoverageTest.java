package io.reductxn.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes every operation the contract promises either served or explicitly recorded as a gap with an owner.
 *
 * <p><strong>Why this exists.</strong> The contract is the source of truth for the client (RULE C1, C3), and the
 * client's browser suite stubs the API <em>from the contract's own generated types</em>. So a promise with nothing
 * behind it is invisible to both suites at once: the stub satisfies the browser tests because the stub was generated
 * from the promise, and the server never noticed because nothing on the server ever asked.
 * {@code GET /groups/{groupId}/members} sat in the contract for seven slices that way, with three screens calling it,
 * while both suites were green. An audit of the contract's operations afterwards found three more reads in the
 * same state - the obligations list, the currency catalogue and the reference rate, all of which the client called -
 * plus the eight the plan already knew about. This test is that audit, run every build, so the next one is found by
 * something that is not a person having a bad feeling about a screen.
 *
 * <p><strong>Three directions, because one direction is how a check like this lies.</strong>
 *
 * <ol>
 *   <li>Every operation in {@code contracts/openapi/reductxn-api.yaml} either has a handler that matches it or appears
 *       in {@link #RECORDED_GAPS} with the thing that will build it. An unlisted operation fails the build, which is
 *       the point: a gap nobody wrote down is a gap nobody is measuring.</li>
 *   <li>Nothing in {@link #RECORDED_GAPS} is now served. A stale entry reads as coverage - the failure the
 *       rule-coverage test's second assertion exists for - and this is the direction that keeps the list honest as
 *       slices land, because every new handler has to delete its own entry.</li>
 *   <li>Every operation a handler serves is one the contract promises. The opposite drift is not quieter: an endpoint
 *       added to a controller and not to the contract is a promise the client cannot see, so the generated types do
 *       not know it exists and no client will ever call it.</li>
 * </ol>
 *
 * <p>Both sides are read as text, because this module sees every module's published classes and none of their
 * sources, and because a Spring mapping is a fact about what a repository contains before it is a fact about what a
 * JVM can load - the reason {@code SchemaOwnershipTest} reads migration files and {@code RuleCoverageTest} reads
 * other modules' tests.
 *
 * <p>Two parsing decisions are worth more than the code around them, and both were made after a wrong report:
 *
 * <ul>
 *   <li><strong>A mapping's arguments are not its path.</strong> The audit's first version took the first quoted
 *       string inside the parentheses, so {@code @GetMapping(produces = ...)} was recorded as an operation at a path
 *       that does not exist and the report cried wolf. Only a bare string, or {@code path =} / {@code value =}, is a
 *       path here; anything else means the mapping inherits the class-level path.</li>
 *   <li><strong>The capture has to be the text inside the parentheses.</strong> The second wrong report came from the
 *       other end of the same annotation: a pattern that captured {@code ("/preview")} whole, parentheses included, so
 *       a mapping that plainly has a path looked like one that does not. Between them, the two reports are the
 *       argument for the sentence in MASTER-PLAN's findings: a tool that goes looking for gaps is not evidence until
 *       it has been made to fail on a case whose answer is known - which, here, meant reading the two controllers and
 *       being able to say which side of the report was wrong.</li>
 *   <li><strong>The base path is stripped, from a declared place.</strong> The contract's paths are relative to the
 *       {@code /v1} in its {@code servers:} block and the controllers declare {@code /v1} themselves, so both sides
 *       are reduced to the same form. That is the only transformation in this file, and it is a fact about the
 *       contract rather than a convention of the parser.</li>
 * </ul>
 *
 * <p><strong>What it does not prove.</strong> It reads a path and a verb, so it cannot tell whether a handler answers
 * what its operation says - a handler that answers {@code 200} with the wrong shape passes here and is caught by the
 * contract test in the module that owns it. Nor does it check that the client calls the operation, which is the other
 * question ADR 0036 asked. It proves one thing, and it was made to fail on cases whose answer was known before it was
 * trusted: a deliberate gap reported as a gap, an entry removed from the list, and something served that the contract
 * does not promise.
 */
class ContractCoverageTest {

    /**
     * A controller's class-level path, and a method's. Both capture the text <em>inside</em> the parentheses.
     *
     * <p>The parentheses-stripping is in the pattern rather than in a {@code substring} call because the first version
     * of this test got it wrong in a way that reported four false operations: capturing {@code ("/preview")} and
     * handing the parentheses to the parser below.
     */
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\s*\\(([^)]*)\\)");

    private static final Pattern METHOD_MAPPING =
            Pattern.compile("@(Get|Post|Put|Patch|Delete)Mapping\\s*(?:\\(([^)]*)\\))?");

    /** A bare string, {@code path =} or {@code value =} - and nothing else, so {@code produces = ...} is not a path. */
    private static final Pattern DECLARED_PATH =
            Pattern.compile("^\\s*\"([^\"]*)\"|\\b(?:path|value)\\s*=\\s*\"([^\"]*)\"");

    /**
     * The operations the contract promises, the controllers do not serve, and somebody owns.
     *
     * <p>Two at the time of writing, and the count is stated as a fact about now rather than as a property of the
     * file, because a number in prose drifts: an earlier version of this paragraph said "the four left when ADR 0044
     * served the activity feed" and was still saying four when the void slice had already made it three. Each entry
     * names the screen, slice or decision that would close it - members, cycles, runs, decisions, transfers, netting,
     * obligations, currencies, rates, balances, tax, invites, sign-in, groups, activity and void have all left this
     * list.
     *
     * <p>{@code POST /fx/quotes} left it too, and not by being built: it was a candidate for removal, and ADR 0046
     * decided it out of the contract rather than serving it. That is the third way an entry can go - after "served by
     * a slice" and "still owned" - and it is worth having in this list's history, because the alternative (building
     * a second door onto a capability the write path already has) would have added surface rather than coverage.
     */
    private static final Map<String, String> RECORDED_GAPS = Map.ofEntries(
            Map.entry("POST /runs/{runId}/execute",
                    "execute - the execution path. The worker plans and computes; performing the transfers is what "
                            + "makes a settlement final, and the rail adapter that would do it is not written"),
            Map.entry("POST /webhooks/rails/{provider}",
                    "webhooks - what a rail tells us asynchronously, which is the answer to an execution that cannot "
                            + "be completed inside the request that started it"));

    @Test
    void everyPromisedOperationIsEitherServedOrRecordedWithItsOwner() throws IOException {
        Set<String> promised = promisedOperations();
        Set<String> served = servedOperations();
        Map<String, String> unlisted = new LinkedHashMap<>();

        for (String operation : promised) {
            if (!served.contains(operation) && !RECORDED_GAPS.containsKey(operation)) {
                unlisted.put(operation, "no handler answers it and RECORDED_GAPS does not name it");
            }
        }

        report(promised, served);

        assertThat(unlisted)
                .as("the contract promises an operation that nothing serves and nobody owns. The client generates "
                        + "its calls from this document, so a promise with no handler is a screen that fails in "
                        + "development and passes both suites (the seven-slice members read). Either add the handler "
                        + "or add an entry to RECORDED_GAPS naming what will build it\n%s", gapReport())
                .isEmpty();
    }

    @Test
    void noRecordedGapIsNowServed() throws IOException {
        Set<String> served = servedOperations();
        Map<String, String> stale = new LinkedHashMap<>();

        for (Map.Entry<String, String> gap : RECORDED_GAPS.entrySet()) {
            if (served.contains(gap.getKey())) {
                stale.put(gap.getKey(), gap.getValue());
            }
        }

        assertThat(stale)
                .as("an operation in RECORDED_GAPS is now served, so the entry reads as a gap that is not one "
                        + "anymore - and a stale entry is how a list like this stops describing the repository. "
                        + "Delete the entry whose handler just landed\n%s", gapReport())
                .isEmpty();
    }

    @Test
    void everyOperationAHandlerAnswersIsOneTheContractPromises() throws IOException {
        Set<String> promised = promisedOperations();
        Map<String, String> unpromised = new LinkedHashMap<>();

        for (String operation : servedOperations()) {
            if (!promised.contains(operation)) {
                unpromised.put(operation, "a handler answers this and the contract does not promise it");
            }
        }

        assertThat(unpromised)
                .as("a controller serves an operation the contract does not declare. The client's types are "
                        + "generated from the contract, so this endpoint is invisible to every client and cannot be "
                        + "called - add it to contracts/openapi/reductxn-api.yaml or delete the handler (RULE C1)")
                .isEmpty();
    }

    /**
     * The operations in the contract, as {@code VERB /path}.
     *
     * <p>The paths are read from the {@code paths:} block alone. A scan of the whole document would also read the
     * schema names under {@code components:}, and a component called {@code get:} at the wrong indent would become an
     * operation that does not exist - the same false report the annotation scan made, on the other side.
     */
    private static Set<String> promisedOperations() throws IOException {
        List<String> lines = Files.readAllLines(
                repositoryRoot().resolve("contracts/openapi/reductxn-api.yaml"));
        Set<String> operations = new TreeSet<>();
        String path = null;
        boolean inPaths = false;

        for (String line : lines) {
            if (line.strip().equals("paths:") && !line.startsWith(" ")) {
                inPaths = true;
                continue;
            }
            // The next top-level key ends the block, and nothing outside it is an operation.
            if (inPaths && !line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            if (!inPaths) {
                continue;
            }

            Matcher pathLine = Pattern.compile("^ {2}(/[^:]*):\\s*$").matcher(line);
            if (pathLine.matches()) {
                path = pathLine.group(1);
                continue;
            }

            Matcher verbLine = Pattern.compile("^ {4}(get|post|put|patch|delete):\\s*$").matcher(line);
            if (verbLine.matches() && path != null) {
                operations.add(verbLine.group(1).toUpperCase(Locale.ROOT) + " " + path);
            }
        }

        return operations;
    }

    /**
     * The operations the controllers answer, as {@code VERB /path}, in the contract's own form.
     *
     * <p>Read from every {@code *Controller.java} under every module, because the ports are not in {@code apps/api}
     * alone - the worker owns a command adapter of its own and a boundary added there would be just as real.
     */
    private static Set<String> servedOperations() throws IOException {
        Set<String> operations = new TreeSet<>();

        for (Path source : controllerSources()) {
            String text = Files.readString(source);
            String base = stripBasePath(declaredPath(argumentsOf(text, CLASS_MAPPING)));

            Matcher mapping = METHOD_MAPPING.matcher(text);
            while (mapping.find()) {
                String sub = declaredPath(mapping.group(2) == null ? "" : mapping.group(2));
                operations.add(mapping.group(1).toUpperCase(Locale.ROOT) + " " + joined(base, sub));
            }
        }

        return operations;
    }

    private static List<Path> controllerSources() throws IOException {
        List<Path> sources = new ArrayList<>();

        for (String container : List.of("libs", "apps", "workers", "platform")) {
            Path containerRoot = repositoryRoot().resolve(container);
            if (!Files.isDirectory(containerRoot)) {
                continue;
            }

            try (Stream<Path> tree = Files.walk(containerRoot)) {
                sources.addAll(tree.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Controller.java"))
                        .filter(path -> path.toString().contains("/src/main/java/"))
                        .toList());
            }
        }

        return sources;
    }

    /**
     * The path a mapping declares, or the empty string.
     *
     * <p>Both patterns capture the text <em>inside</em> a mapping's parentheses, and that is not a detail: the first
     * version of this test captured the parentheses themselves, so {@code @GetMapping("/preview")} arrived here as
     * {@code ("/preview")}, matched nothing, and was recorded as a mapping with no path. Three operations were
     * reported as un-promised handlers and one as an unowned gap, all four from the same missing parentheses-strip -
     * the second wrong report this check produced, after the one that read {@code produces = ...} as a path.
     *
     * <p>{@code @GetMapping} with no arguments inherits the class-level path, and so does
     * {@code @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)}: only a bare string, or {@code path =} /
     * {@code value =}, is a path here.
     */
    private static String declaredPath(String arguments) {
        Matcher declared = DECLARED_PATH.matcher(arguments);

        if (!declared.find()) {
            return "";
        }

        return declared.group(1) != null ? declared.group(1) : declared.group(2);
    }

    /** The text inside a mapping's parentheses, or the empty string when it has none. */
    private static String argumentsOf(String text, Pattern mapping) {
        Matcher match = mapping.matcher(text);

        return match.find() ? match.group(1) : "";
    }

    /**
     * The contract's paths are relative to the {@code /v1} in its {@code servers:}; the controllers declare {@code /v1}
     * themselves. Both sides are reduced to the same form here - the only transformation in this file, and one the
     * contract states rather than one this parser chose.
     */
    private static String stripBasePath(String declared) {
        return declared.startsWith("/v1") ? declared.substring("/v1".length()) : declared;
    }

    private static String joined(String base, String sub) {
        String joined = base + sub;

        return joined.isEmpty() ? "/" : joined;
    }

    /**
     * The whole picture, printed on every run and included in an assertion's message.
     *
     * <p>The locale check reports the same way: what passes is stated, and what is deferred is named with its reason
     * rather than hidden, so the list is read by whoever runs the build rather than only by whoever breaks it.
     */
    private static void report(Set<String> promised, Set<String> served) {
        System.out.println("The contract is covered: " + promised.size() + " operations, " + served.size()
                + " served, " + RECORDED_GAPS.size() + " recorded with an owner.");

        for (Map.Entry<String, String> gap : new TreeMap<>(RECORDED_GAPS).entrySet()) {
            System.out.println("  gap " + gap.getKey() + " - " + gap.getValue());
        }
    }

    private static String gapReport() {
        StringBuilder report = new StringBuilder("\nthe recorded gaps are:");

        for (Map.Entry<String, String> gap : new TreeMap<>(RECORDED_GAPS).entrySet()) {
            report.append("\n  ").append(gap.getKey()).append(" - ").append(gap.getValue());
        }

        return report.toString();
    }

    private static Path repositoryRoot() {
        return Repository.root();
    }
}
