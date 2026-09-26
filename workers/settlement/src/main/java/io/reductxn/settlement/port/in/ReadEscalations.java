package io.reductxn.settlement.port.in;

import java.time.Instant;

/**
 * Asking what the worker is holding that no rail will decide: the other half of section 4.4's "or escalate".
 *
 * <p><strong>Why this is a read and not a use case with a side effect.</strong> Escalation in this product means
 * telling a human, and the two ways it happens are the cadence's log line and the operator's command line - both
 * of which are adapters over this port. Nothing is written: the transfers are the evidence, and an
 * {@code escalated_at} column would be a memory of a log line that is still true on every later pass. A silence
 * that has not ended is still happening, and a report that mentioned it once would let the second hour pass
 * unnoticed.
 *
 * <p><strong>The cut-off is the caller's, because the policy is the deployment's.</strong> {@code Escalation} turns
 * "how long is too long" into an instant, and this port answers a question about rows at or before it - the same
 * division the driver's window uses, and the reason a test can assert a two-hour rule without any clock moving.
 */
public interface ReadEscalations {

    /**
     * The transfers whose rail went silent at or before {@code cutOff}, oldest silence first.
     *
     * <p>{@code total} is counted rather than inferred from the returned list, so a truncated report says how many
     * it did not show - the number an operator decides with.
     *
     * @param cutOff the instant the policy drew the line at
     * @param limit  the most transfers to list, so a rail that has been down for a month cannot turn a log line
     *               into a novel. The oldest are taken first
     */
    Escalations silentLongerThan(Instant cutOff, int limit);
}
