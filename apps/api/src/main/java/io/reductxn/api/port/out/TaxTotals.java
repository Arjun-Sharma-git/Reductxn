package io.reductxn.api.port.out;

import io.reductxn.api.port.in.TaxReportLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The plan's tax figures, summed per currency over a range of cycle instants.
 *
 * <p><strong>Half-open, and that is the port's own convention rather than the caller's.</strong> {@code from} is
 * inclusive and {@code to} is exclusive, so an instant sits in exactly one range and a report for adjacent periods
 * cannot count a cycle twice or miss it. The days a caller asks in are converted to this range one layer in; a port
 * that took days would be a port that had decided what a day means to a database, which is a decision about the
 * caller's timezone.
 *
 * <p>{@code null} on either bound means unbounded, and a group with no computed plans is an empty list.
 *
 * <p>It answers {@link TaxReportLine}, the same type the use case answers with, for the reason the members and runs
 * reads give: an adapter that returned an intermediate shape would be a second description of a report line, and one
 * of the two would be wrong eventually.
 */
public interface TaxTotals {

    /** Per-currency totals over the plans whose cycle instant is in {@code [from, to)}, ordered by currency. */
    List<TaxReportLine> totals(UUID groupId, Instant from, Instant to);
}
