package io.reductxn.ledger.api;

/**
 * The side of a double-entry posting.
 *
 * <p>Direction, not a sign, encodes debit versus credit. Amounts are always positive, so a posting
 * can never be ambiguous about which side it belongs to.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
