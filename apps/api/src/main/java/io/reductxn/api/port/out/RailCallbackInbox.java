package io.reductxn.api.port.out;

import io.reductxn.api.port.in.NewRailCallback;

/**
 * The write behind {@code POST /webhooks/rails/{provider}}.
 *
 * <p><strong>This module writes a table it does not own, for the third time, and for the same reason.</strong>
 * {@code settlement_rail_callbacks} belongs to {@code workers/settlement} - its migration is in that module's range -
 * and it exists so that a delivered answer is where the driver looks for one. A rail can only reach a web surface, and
 * the only web surface here is this module; the alternative to a shared table is an inter-deployable call, which RULE B6
 * forbids (ADR 0020, 0021, 0050, 0051).
 *
 * <p><strong>It is an upsert, and that is the endpoint's promised idempotency.</strong> The reference is the primary
 * key, so a redelivery rewrites the rail's word and the instant rather than appending a second opinion about the same
 * transfer. Nothing here reads the table back: what the worker does with the row is the worker's answer to give.
 */
public interface RailCallbackInbox {

    /**
     * Records the rail's word about this reference, replacing whatever it said before.
     *
     * @param callback the verified callback, with its fields as the rail wrote them
     */
    void record(NewRailCallback callback);
}
