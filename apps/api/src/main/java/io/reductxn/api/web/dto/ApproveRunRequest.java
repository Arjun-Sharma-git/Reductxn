package io.reductxn.api.web.dto;

import java.util.UUID;

/**
 * The contract's request body for {@code POST /runs/{runId}/approve}.
 *
 * <p><strong>The contract had no body for this operation, and that was not implementable.</strong> "Approve a
 * computed run" with only a path parameter describes an approval by nobody: a quorum is a count of *members*, so an
 * answer has to name one. This adds the field the operation was missing rather than inventing a shape - the
 * obligation request already names its parties the same way - and it is the honest place for the identity gap to
 * show: until there is authentication, the member is whoever the client says, exactly as an obligation's debtor is.
 *
 * <p>No bean-validation annotations, for the reason {@code RecordObligationRequest} gives: without a validation
 * provider they would be silently inert, and an annotation that reads like a check but is not one is worse than no
 * annotation. The controller checks what it needs and answers a malformed request with a {@code 400} carrying a
 * code, not with Spring's own error body.
 */
public record ApproveRunRequest(UUID memberId) {
}
