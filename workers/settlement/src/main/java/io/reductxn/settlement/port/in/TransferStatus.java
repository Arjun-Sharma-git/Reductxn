package io.reductxn.settlement.port.in;

import java.util.Locale;

/**
 * Where a single transfer stands, as docs/specs/settlement.md section 1 writes it:
 *
 * <pre>
 *   pending --> authorized --> submitted --> confirmed
 *                                   \-> failed
 * </pre>
 *
 * <p>Separate from {@link RunStatus} because they are separate questions. A run can be {@code executing}
 * while its transfers are in four different states, and a run that is {@code failed} has transfers that are
 * {@code confirmed} - money that moved and cannot be un-moved (RULE S3).
 *
 * <p><strong>{@code authorized} is not decoration.</strong> It is the state a transfer is in when quorum has
 * authorised it and the rail has not yet been called, and it exists in the schema for the reason section 4.2
 * gives: every state transition is persisted <em>before</em> the next call is made. A worker that wrote
 * "submitted" only after the rail answered could crash between the money moving and the record of it, and the
 * record is the only thing that would let anyone reconcile.
 */
public enum TransferStatus {

    PENDING,
    AUTHORIZED,
    SUBMITTED,
    CONFIRMED,
    FAILED;

    /** The wire and column form: lower case, as the spec and the {@code CHECK} constraint write it. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The state a stored transfer is in, read back from its column.
     *
     * <p>An exhaustive switch rather than a lookup, as {@link RunStatus#ofWireName} is, and for the same
     * reason: a state added without teaching this method about it breaks the build instead of reading a row
     * as {@code pending}.
     */
    public static TransferStatus ofWireName(String wireName) {
        return switch (wireName) {
            case "pending" -> PENDING;
            case "authorized" -> AUTHORIZED;
            case "submitted" -> SUBMITTED;
            case "confirmed" -> CONFIRMED;
            case "failed" -> FAILED;
            default -> throw new IllegalArgumentException(
                    "unknown transfer status '" + wireName + "'. The lifecycle is written down in "
                            + "docs/specs/settlement.md section 1 and the column has a CHECK constraint, so "
                            + "this means the row came from somewhere other than this module");
        };
    }

    /** Whether this transfer needs no further work from the worker. */
    public boolean isResolved() {
        return this == CONFIRMED || this == FAILED;
    }

    /**
     * Whether the rail has this transfer and is being asked for it again would be a duplicate.
     *
     * <p>{@code submitted} is the "at the rail, not yet answered" state: a re-submission would be a no-op by
     * RULE S2, so it is not a correctness risk, but asking the rail for the status is the honest question -
     * the money may already have moved.
     */
    public boolean isAtTheRail() {
        return this == SUBMITTED;
    }
}
