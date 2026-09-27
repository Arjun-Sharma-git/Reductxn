package io.reductxn.api.application.tax;

import io.reductxn.api.port.in.GroupRefusals;
import io.reductxn.api.port.in.ReadTaxReport;
import io.reductxn.api.port.in.TaxReportLine;
import io.reductxn.api.port.out.Groups;
import io.reductxn.api.port.out.TaxTotals;
import io.reductxn.kernel.api.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The use case: the tax a group's plans have assessed over a period.
 *
 * <p><strong>This service owns one decision: what a day means.</strong> The port takes the two dates a caller
 * asks in and the read model takes a half-open instant range, so somebody has to say where a day begins - and this is
 * the layer where that belongs, because it is a statement about the caller's question rather than about storage or
 * about HTTP. {@code periodStart} is that day's midnight UTC and {@code periodEnd} is the midnight after it, which is
 * what makes the end bound *inclusive*: a cycle closed at 23:59 on the end day is in the period, and one closed at
 * 00:00 on the next day is not.
 *
 * <p>UTC is a stand-in rather than a decision, and it is the same one the obligations list records: the spec says the
 * cycle end date is evaluated in the group's settlement timezone (`docs/specs/tax-rules.md` section 4), and a group's
 * timezone is a column that a group may leave unchosen (ADR 0043). When every group has one, this is the method that
 * changes - and it will be the only one, because the days stop being instants here and nowhere else.
 *
 * <p>A null bound stays null through to the SQL: no range on one side means unbounded rather than "from the epoch",
 * which matters because the epoch bound would read as a filter and answer as one.
 *
 * <p><strong>Membership is the condition since ADR 0054, and the check comes before the days are converted.</strong>
 * The group arrives as a query parameter rather than a path segment, which is why this read was the one a rule about
 * paths would have missed - and the ordering is the same discipline ADR 0049 established for a write: a stranger gets
 * the `404` without this service having looked at a single tax total.
 */
@Service
public class ReadTaxReportService implements ReadTaxReport {

    private final Groups groups;
    private final TaxTotals totals;

    public ReadTaxReportService(Groups groups, TaxTotals totals) {
        this.groups = Objects.requireNonNull(groups, "groups");
        this.totals = Objects.requireNonNull(totals, "totals");
    }

    @Override
    @Transactional(readOnly = true)
    public Result<List<TaxReportLine>> report(UUID callerId, UUID groupId, LocalDate periodStart, LocalDate periodEnd) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(groupId, "groupId");

        if (!groups.isMember(groupId, callerId)) {
            return Result.fail(new GroupRefusals.NoSuchGroup(groupId));
        }

        return Result.ok(totals.totals(groupId, from(periodStart), toExclusive(periodEnd)));
    }

    /** The first instants counted: midnight UTC on the start day, or nothing. */
    private static Instant from(LocalDate periodStart) {
        return periodStart == null ? null : periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * The first instant *not* counted.
     *
     * <p>The day after the end day, so the end day is included in full. A report that ended at the end day's own
     * midnight would silently exclude everything settled on the day the reader asked for, which is the day they are
     * most likely to care about.
     */
    private static Instant toExclusive(LocalDate periodEnd) {
        return periodEnd == null ? null : periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
