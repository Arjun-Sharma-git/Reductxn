package io.reductxn.netting.api;

/**
 * Which way a member's net position points after netting.
 *
 * <p>Distinct from {@code io.reductxn.ledger.api.Direction}, which describes the debit/credit side
 * of a posting. This type describes an outcome for a person: you pay, you receive, or you are flat.
 */
public enum NetDirection {
    PAY,
    RECEIVE,
    FLAT
}
