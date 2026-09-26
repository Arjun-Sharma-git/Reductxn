package io.reductxn.settlement.port.in;

import java.time.Instant;

/**
 * Asking what the worker is holding that no rail will decide: section 4.4's "or escalate".
 *
 * <p><strong>Why this is a read and not a use case with a side effect.</strong> Escalation in this product means
 * telling a human, and the two ways it happens are the cadence's log line and the operator's command line - both of
 * which are adapters over this port. Nothing is written: the transfers are the evidence, and an {@code escalated_at}
 * column would be a memory of a log line that is still true on every later pass. A silence that has not ended is
 * still happening, and a report that mentioned it once would let the second hour pass unnoticed.
 *
 * <p><strong>The cut-offs are the caller's, because the policies are the deployment's.</strong> {@link Escalation}
 * turns "how long may a rail go unanswered" into an instant and {@link Reconciliation} turns "how long may a
 * submission wait for confirmation" into another, and this port answers a question about rows at or before each -
 * the same division the driver's window uses, and the reason a two-hour and a one-hour rule can both be asserted in
 * a test without any clock moving.
 *
 * <p><strong>One question, two lines drawn.</strong> A transfer is in the report when it crosses either, and the row
 * says which one it crossed and since when ({@code StuckTransfer}). Passing both instants rather than one keeps the
 * decision about *which* transfers matter in the two policies, where a deployment can see and set it, instead of
 * inside a query.
 */
public interface ReadStuckTransfers {

    /**
     * The transfers no rail has decided, oldest trouble first.
     *
     * <p>{@code total} is counted rather than inferred from the returned list, so a truncated report says how many it
     * did not show - the number an operator decides with.
     *
     * @param unansweredCutOff  a transfer with an unanswered call at or before this is in the report
     * @param unconfirmedCutOff a transfer the rail accepted at or before this and has still not confirmed is in it
     * @param limit             the most transfers to list, so a rail that has been down for a month cannot turn a log
     *                          line into a novel. The oldest are taken first
     */
    StuckTransfers stuckLongerThan(Instant unansweredCutOff, Instant unconfirmedCutOff, int limit);
}
