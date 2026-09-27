package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The use case: the tax a group's settlement plans have assessed over a period.
 *
 * <p><strong>The period is days, and both bounds are inclusive.</strong> They match the cycle's {@code as_of} rather
 * than the run's creation, because {@code as_of} is the instant the books were closed and the instant every rate and
 * tax rule was resolved at - so it is the date a tax belongs to. A period is a human interval ("September"), not an
 * instant pair, which is why the parameters are dates and the conversion to a half-open instant range happens one
 * layer in, where the SQL can be read against it.
 *
 * <p>{@code null} for either bound means no bound, so a caller who asks for nothing gets everything the group has ever
 * planned. The two are not validated against each other: a period that ends before it starts is an empty report rather
 * than a refusal, which is what the obligations list does with the same pair of parameters and what a report can
 * honestly answer.
 *
 * <p><strong>A {@code Result} since ADR 0054.</strong> This port said "nothing is a {@code Result}: every narrowing can
 * be answered and a group with no plans is an empty report - the same sentence the runs, cycles, members and balances
 * reads end with, because groups are not tables in this build". Groups have been tables since ADR 0043 and the caller
 * must be a member of the one being reported on, so the answer is the lines or
 * {@link GroupRefusals.NoSuchGroup}. Worth naming because of how this read is addressed: its group arrives as a query
 * parameter rather than a path segment, so it was the one the slice could only find by walking the operations rather
 * than by reading the paths.
 */
public interface ReadTaxReport {

    /**
     * The per-currency lines of the report, ordered by currency.
     *
     * @param periodStart the first day counted, inclusive; null for unbounded
     * @param periodEnd   the last day counted, inclusive of the whole of that day; null for unbounded
     */
    Result<List<TaxReportLine>> report(UUID callerId, UUID groupId, LocalDate periodStart, LocalDate periodEnd);
}
