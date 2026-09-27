package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadTaxReport;
import io.reductxn.api.port.in.ReadTaxRules;
import io.reductxn.api.port.in.RunRefusals;
import io.reductxn.api.port.in.SignedInUser;
import io.reductxn.api.port.in.TaxReportLine;
import io.reductxn.api.web.CallerResolver;
import io.reductxn.api.web.dto.TaxMapper;
import io.reductxn.api.web.dto.TaxRuleResponse;
import io.reductxn.api.web.error.ExpectedFailures;
import io.reductxn.kernel.api.DomainError;
import io.reductxn.kernel.api.Result;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code GET /v1/tax/rules} and {@code GET /v1/tax/reports}, per the contract.
 *
 * <p>One controller for both, because they are one resource in the document - the contract tags them `tax` and gives
 * them one prefix - and because a controller per path here would be two classes that inject the same two ports.
 *
 * <p><strong>It reads the clock for the rules read, and nowhere else.</strong> A rule's window is effective-dated, so
 * "which rules are in force" is a question about an instant; the boundary decides that instant, exactly as the
 * balances and preview controllers do, so a test can pin the clock and get a reproducible answer. The report takes its
 * period from the caller instead - a report is about a period somebody chose, and defaulting it to "now" would answer
 * a question nobody asked.
 *
 * <p><strong>The two operations differ in who asks, and since ADR 0054 that is the line between them.</strong> The
 * rules read is reference data: which rules are in force for this deployment's jurisdiction is the same answer for
 * everybody, names no group and stays open. The report is a group's own figures, and its group arrives as a
 * **query parameter** - so it is the read that no rule about paths would have found, and the caller must be a member
 * of the group being reported on.
 *
 * <p><strong>Malformed path and query values are checked here rather than bound, since ADR 0054.</strong> A bound
 * {@code UUID} is judged by Spring before the handler runs, which would answer a malformed group id with a `400`
 * *before* the session was resolved - telling unauthenticated callers something about their request when every other
 * answer on this endpoint is `401`. The `periodStart`/`periodEnd` dates are still bound, deliberately: they are not
 * part of a group's identity, and a malformed date is a `400` whoever is asking.
 */
@RestController
@RequestMapping("/v1/tax")
public class TaxController {

    private final ReadTaxRules rules;
    private final ReadTaxReport report;
    private final TaxMapper mapper;
    private final CallerResolver callers;
    private final Clock clock;

    public TaxController(ReadTaxRules rules, ReadTaxReport report, TaxMapper mapper, CallerResolver callers, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.report = Objects.requireNonNull(report, "report");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.callers = Objects.requireNonNull(callers, "callers");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Which rules are in force now, for this deployment's jurisdiction. */
    @GetMapping("/rules")
    public List<TaxRuleResponse> rules() {
        return rules.inForce(clock.instant()).stream().map(mapper::toResponse).toList();
    }

    /**
     * The tax a group's plans have assessed over a period.
     *
     * @param periodStart optional; null means no earlier bound rather than the epoch
     * @param periodEnd   optional and inclusive of the whole of that day
     */
    @GetMapping("/reports")
    public ResponseEntity<Object> report(
            @RequestParam("groupId") String groupId,
            @RequestParam(name = "periodStart", required = false) LocalDate periodStart,
            @RequestParam(name = "periodEnd", required = false) LocalDate periodEnd,
            HttpServletRequest http) {

        // Who is asking first, since ADR 0054. The group arrives as a query parameter rather than as a path segment,
        // which is why this read had to be found by walking the operations rather than the paths - and the ordering is
        // the one every other group read uses: a request with no session is a 401 before a malformed id is a 400.
        Result<SignedInUser> caller = callers.resolve(http);

        if (caller instanceof Result.Failure<SignedInUser> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        DomainError malformed = malformedGroupId(groupId);

        if (malformed != null) {
            return problem(malformed, http.getRequestURI());
        }

        Result<List<TaxReportLine>> lines = report.report(
                ((Result.Ok<SignedInUser>) caller).value().id(),
                UUID.fromString(groupId),
                periodStart,
                periodEnd);

        if (lines instanceof Result.Failure<List<TaxReportLine>> refused) {
            return problem(refused.error(), http.getRequestURI());
        }

        return ResponseEntity.ok(((Result.Ok<List<TaxReportLine>>) lines).value()
                .stream()
                .map(TaxMapper::toResponse)
                .toList());
    }

    /**
     * The query parameter, checked here rather than bound, so a malformed group id gets a coded problem body rather
     * than Spring's own uncoded {@code 400} - the compromise every other controller in this module makes.
     */
    private static DomainError malformedGroupId(String groupId) {
        if (groupId == null || groupId.isBlank() || !isUuid(groupId)) {
            return new RunRefusals.RequestIncomplete("groupId, as a uuid");
        }

        return null;
    }

    private static boolean isUuid(String candidate) {
        try {
            UUID.fromString(candidate);

            return true;
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    /** A refusal as the response the contract describes, with the status its code carries. */
    private static ResponseEntity<Object> problem(DomainError failure, String instance) {
        var problem = ExpectedFailures.toProblem(failure, instance);

        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
