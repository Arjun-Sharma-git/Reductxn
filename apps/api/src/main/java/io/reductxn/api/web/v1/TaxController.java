package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadTaxReport;
import io.reductxn.api.port.in.ReadTaxRules;
import io.reductxn.api.web.dto.TaxMapper;
import io.reductxn.api.web.dto.TaxReportLineResponse;
import io.reductxn.api.web.dto.TaxRuleResponse;

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
 * <p><strong>Malformed parameters never reach the methods below.</strong> A {@code UUID} or {@code LocalDate} that
 * cannot be parsed is answered by Spring with a {@code 400} problem body
 * ({@code spring.mvc.problemdetails.enabled}), which is what the contract's response list promises.
 */
@RestController
@RequestMapping("/v1/tax")
public class TaxController {

    private final ReadTaxRules rules;
    private final ReadTaxReport report;
    private final TaxMapper mapper;
    private final Clock clock;

    public TaxController(ReadTaxRules rules, ReadTaxReport report, TaxMapper mapper, Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.report = Objects.requireNonNull(report, "report");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
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
    public List<TaxReportLineResponse> report(
            @RequestParam("groupId") UUID groupId,
            @RequestParam(name = "periodStart", required = false) LocalDate periodStart,
            @RequestParam(name = "periodEnd", required = false) LocalDate periodEnd) {

        return report.report(groupId, periodStart, periodEnd).stream()
                .map(TaxMapper::toResponse)
                .toList();
    }
}
