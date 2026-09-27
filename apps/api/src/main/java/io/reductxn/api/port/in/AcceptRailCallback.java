package io.reductxn.api.port.in;

import io.reductxn.kernel.api.Result;

/**
 * The use case: record what a rail told us about a transfer, without being asked.
 *
 * <p><strong>It is the other end of an operation that existed on one side only.</strong> Section 4.4's reconciliation
 * has always been a poll - the driver asks a rail about a transfer it has not confirmed - and this is the rail
 * answering by itself. What it writes is a row in {@code settlement_rail_callbacks}, which
 * {@code workers/settlement}'s driver reads and prefers to a poll (ADR 0051), so the effect is that a rail which
 * volunteers an answer is not asked to repeat it.
 *
 * <p><strong>Nothing about money is decided here, and nothing here can decide anything about money.</strong> This use
 * case records a rail's word; the transfer's state, the run's fate and the ledger entry are all the worker's, reached
 * only through the answer being read. That is why the reference is not checked against the worker's transfers: a row
 * for a reference nothing asks about is inert, and checking would mean this module reading another module's rows to
 * write a row of its own.
 *
 * <p>Idempotency is the schema's, not a check here: a redelivered callback - which every rail will send, because a
 * callback is an at-least-once signal - rewrites the reference's row rather than adding a second one (V513).
 */
public interface AcceptRailCallback {

    /**
     * Records the callback, or refuses it for the one reason the boundary did not already decide.
     *
     * @param callback a callback whose signature has been verified, with its fields still as the rail wrote them
     * @return what was recorded, or a refusal naming the field this build could not use
     */
    Result<RecordedRailCallback> accept(NewRailCallback callback);
}
