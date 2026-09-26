package io.reductxn.settlement.port.in;

import java.util.Objects;
import java.util.UUID;

/**
 * One member's answer on a run (RULE S1).
 *
 * <p><strong>A decision is final.</strong> A member who has answered is refused a second answer rather than
 * being allowed to change their mind, and the reason is the alternative: if approvals could be edited, the run
 * would need a rule about whether a withdrawal of consent invalidates approvals granted after it, and the only
 * honest version of that rule is "yes, and we would have to re-derive the run's state on every edit". A
 * settlement is a decision the group is asked for once; a group that needs to change its answer can say no and
 * open another cycle over the same commitments, which costs one refusal and leaves the record readable.
 *
 * @param runId    the run being answered
 * @param memberId who is answering. Must be one of the run's own participants, or the approval does not count
 *                 and is refused: the participants are the people whose money this run moves, and a quorum any
 *                 passer-by could satisfy is not a quorum
 * @param decision approve or reject
 */
public record RunDecision(UUID runId, UUID memberId, Decision decision) {

    public enum Decision {

        APPROVE,
        REJECT;

        /** The wire and column form, as the schema's {@code CHECK} constraint writes it. */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** The inverse, from a stored row. Exhaustive, so a new decision cannot be read as an approval. */
        public static Decision ofWireName(String wireName) {
            return switch (wireName) {
                case "approve", "approved" -> APPROVE;
                case "reject", "rejected" -> REJECT;
                default -> throw new IllegalArgumentException(
                        "unknown decision '" + wireName + "'. The column has a CHECK constraint, so this "
                                + "means the row came from somewhere other than this module");
            };
        }
    }

    public RunDecision {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(decision, "decision");
    }

    /** Whether this answer is the one that ends the run. */
    public boolean isRejection() {
        return decision == Decision.REJECT;
    }
}
