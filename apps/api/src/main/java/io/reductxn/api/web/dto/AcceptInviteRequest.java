package io.reductxn.api.web.dto;

/**
 * The contract's `AcceptInvite` request: a code holder saying who they are.
 *
 * <p><strong>Its existence is the recorded identity gap, said as a schema.</strong> The operation had no request body at
 * all, which left the server with no way to know who was accepting - and no authentication to find out from. The body is
 * therefore who the caller says they are, exactly as the decision endpoint's body is (ADR 0020), and the contract's
 * description says so where a reader will see it. When sign-in exists, this field disappears and the acceptance becomes
 * about the caller rather than about the body.
 *
 * <p>No `role`, for the reason {@code AcceptedInvitation} gives: accepting joins as `member`. No validation here either,
 * which is the same stance {@code MemberRequest} takes - what counts as malformed is decided at the boundary, where the
 * contract is being honoured.
 */
public record AcceptInviteRequest(String userId, String displayName) {
}
