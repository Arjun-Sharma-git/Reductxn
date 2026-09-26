package io.reductxn.settlement.port.in;

/**
 * The run lifecycle, as docs/specs/settlement.md section 1 writes it:
 *
 * <pre>
 *   pending --> computing --> awaiting_approval --> executing --> done
 *                                     |                 |
 *                                     +-> rejected      +-> failed (partial)
 *                                     |
 *                                     +-> awaiting_fx_funding --> executing
 * </pre>
 *
 * <p>The whole vocabulary is here, and only part of it is reachable. That is deliberate rather than
 * unfinished: a lifecycle is a written-down agreement about what states mean, and the alternative - adding
 * states as they are needed - is how {@code netted} and {@code settled} ended up in the contract before
 * anything could produce them. Reachable today:
 *
 * <ul>
 *   <li>{@link #PENDING} and {@link #COMPUTING} - written while a run is opened and its plan computed, in one
 *       transaction, so neither is observable from outside it. They are stored because a run that is
 *       interrupted mid-computation has to be identifiable rather than absent.</li>
 *   <li>{@link #AWAITING_APPROVAL} - the plan is computed and the group has not approved it. RULE S1's quorum
 *       is what moves it on, and quorum does not exist yet.</li>
 *   <li>{@link #AWAITING_FX_FUNDING} - RULE S4: some currency's {@code fx_clearing} position is non-zero, so
 *       the group has to bring that currency in from outside. A real blocking state, not a warning.</li>
 * </ul>
 *
 * <p>{@link #EXECUTING}, {@link #DONE}, {@link #REJECTED} and {@link #FAILED} need a rail and a quorum, which
 * is the next slice rather than this one. {@code FAILED} is specifically "failed (partial)": reduction
 * partially applied is a first-class outcome (RULE S3), never a rollback, because money that has moved cannot
 * be unmoved.
 */
public enum RunStatus {

    PENDING,
    COMPUTING,
    AWAITING_APPROVAL,
    AWAITING_FX_FUNDING,
    EXECUTING,
    DONE,
    REJECTED,
    FAILED;

    /** The wire and column form: lower case with underscores, as the spec writes the states. */
    public String wireName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The state a stored run is in, read back from its column.
     *
     * <p>An exhaustive switch rather than a lookup table: adding a state without teaching this method about it
     * then breaks the build instead of silently reading a run as {@code pending}. A default case would do the
     * opposite, which is why there is none.
     */
    public static RunStatus ofWireName(String wireName) {
        return switch (wireName) {
            case "pending" -> PENDING;
            case "computing" -> COMPUTING;
            case "awaiting_approval" -> AWAITING_APPROVAL;
            case "awaiting_fx_funding" -> AWAITING_FX_FUNDING;
            case "executing" -> EXECUTING;
            case "done" -> DONE;
            case "rejected" -> REJECTED;
            case "failed" -> FAILED;
            default -> throw new IllegalArgumentException(
                    "unknown run status '" + wireName + "'. The lifecycle is written down in "
                            + "docs/specs/settlement.md section 1 and the column has a CHECK constraint, so "
                            + "this means the row came from somewhere other than this module");
        };
    }

    /**
     * Whether the run is waiting for the group to do something rather than for the worker.
     *
     * <p>Both waiting states are real: {@code awaiting_approval} waits for RULE S1's quorum, and
     * {@code awaiting_fx_funding} waits for currency the group does not hold (RULE S4). A caller that looked at
     * "is it waiting" alone would tell a group it had approved a run that is still unfunded.
     */
    public boolean isWaitingOnTheGroup() {
        return this == AWAITING_APPROVAL || this == AWAITING_FX_FUNDING;
    }
}
