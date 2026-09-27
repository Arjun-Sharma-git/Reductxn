package io.reductxn.api.web.dto;

/**
 * The contract's `AcceptInvite` request: what somebody joining supplies, which is the name the group will call them.
 *
 * <p><strong>It used to carry a `userId`, and dropping it is the contract's own promise being kept.</strong> The
 * operation's description said "when sign-in exists, this body disappears and the acceptance becomes about the caller",
 * and this build has sign-in (ADR 0042): the person joining is whoever holds the session, so the only thing left for the
 * body to say is what the group should call them.
 *
 * <p>A `userId` in the body is now <strong>ignored rather than refused</strong>. The caller cannot name somebody else,
 * and answering a field that has no effect with a `400` would break a client that still sent one while protecting
 * nothing; the test that a body carrying one joins the *session's* person is what makes the ignoring deliberate instead
 * of accidental.
 *
 * <p>No `role`, for the reason {@code AcceptedInvitation} gives: accepting joins as `member`. No validation here either,
 * which is the same stance {@code MemberRequest} takes - what counts as malformed is decided at the boundary, where the
 * contract is being honoured.
 */
public record AcceptInviteRequest(String displayName) {
}
