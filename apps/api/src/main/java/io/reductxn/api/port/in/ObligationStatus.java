package io.reductxn.api.port.in;

/**
 * Where an obligation has got to, matching the contract's {@code status} enum.
 *
 * <p>Four values because the contract has four, and only two are producible today: an obligation is
 * {@link #OPEN} the moment it is recorded, and {@link #VOIDED} once {@code voided_at} is set. {@link #NETTED}
 * and {@link #SETTLED} need a settlement run, and {@code workers/settlement} has no sources - so this enum
 * states the whole vocabulary while the record path can only reach the first two. That is deliberate: a
 * three-value enum here would be a contract the server cannot satisfy the day somebody reads an obligation
 * that a run has touched.
 */
public enum ObligationStatus {

    /** Recorded and still owed. The state every newly recorded obligation is in. */
    OPEN,

    /** Included in a settlement run. Not producible until the worker exists. */
    NETTED,

    /** Settled by a run. Not producible until the worker exists. */
    SETTLED,

    /** Voided rather than deleted (ADR 0004). */
    VOIDED
}
