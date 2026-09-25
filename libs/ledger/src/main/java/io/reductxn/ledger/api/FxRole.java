package io.reductxn.ledger.api;

/**
 * Which side of a conversion a linked entry is.
 *
 * <p>A cross-currency obligation becomes two single-currency entries joined by an {@code fx_link_id}
 * (ADR 0012 point 3), one per book, with the {@code fx_clearing} account balancing each side. One entry
 * is the base side and the other the quote side.
 *
 * <p>The role exists because a hash chain that verifies in one currency proves nothing about the other.
 * Pairing by {@code fx_link_id} alone would accept two base entries, or a base without a quote; pairing
 * by role is what makes L9 checkable across chains rather than within one.
 */
public enum FxRole {

    /** The side denominated in the currency the obligation was recorded in. */
    BASE,

    /** The side denominated in the currency the creditor is to be paid in. */
    QUOTE
}
