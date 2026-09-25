package io.reductxn.ledger.api;

/**
 * What a journal entry records, matching the {@code kind} column in {@code docs/specs/ledger.md}.
 *
 * <p>A closed set rather than a string, so a typo is a compile error and the set of things the ledger
 * can record is visible in one place. Report queries branch on this, and a free string would make a
 * misspelt kind silently invisible to them.
 *
 * <p>Deliberately orthogonal to the FX linkage fields on {@link JournalEntry}. A cross-currency
 * obligation produces a linked pair whose kind is {@link #OBLIGATION} - the entries record an
 * obligation that happens to be settled in two currencies - while a correction to a rate is
 * {@link #FX} and has no link at all. Coupling the two would have meant deciding which of those
 * governs, and ADR 0012 decides neither.
 */
public enum EntryKind {

    /** A member's commitment, or the part of one recorded in a single book. */
    OBLIGATION,

    /** Movement of funds against a settlement run. */
    SETTLEMENT,

    /** A platform fee. */
    FEE,

    /** An assessed tax. */
    TAX,

    /** A conversion, including the booking of a rounding residual. */
    FX,

    /** A manual correction. Corrections are compensating entries, never updates (ADR 0004). */
    ADJUSTMENT
}
