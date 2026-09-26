package io.reductxn.api.web.dto;

import java.util.UUID;

/**
 * The contract's request body for {@code POST /runs/{runId}/decisions}.
 *
 * <p><strong>The contract had no body for this operation, and that was not implementable.</strong> "Approve a
 * computed run" with only a path parameter describes an approval by nobody: a quorum is a count of *members*, so an
 * answer has to name one. This adds the field the operation was missing rather than inventing a shape - the
 * obligation request already names its parties the same way - and it is the honest place for the identity gap to
 * show: until there is authentication, the member is whoever the client says, exactly as an obligation's debtor is.
 *
 * <p><strong>{@code decision} is the answer, and it is optional because yes is the common one.</strong> Absent means
 * approve, which is what the request meant before a refusal was expressible; the mapper applies that default, so
 * the wire format's silence has exactly one interpretation and the use case never sees it. The word itself is the
 * run's rather than this module's - {@code approve} or {@code reject}, as the migration's check constraint writes
 * them - and it is carried through rather than validated here: the row refuses a word that is not in the vocabulary,
 * and a coded refusal from the store is a better answer than a second copy of the list in Java.
 *
 * <p>No bean-validation annotations, for the reason {@code RecordObligationRequest} gives: without a validation
 * provider they would be silently inert, and an annotation that reads like a check but is not one is worse than no
 * annotation. The controller checks what it needs and answers a malformed request with a {@code 400} carrying a
 * code, not with Spring's own error body.
 */
public record DecisionRequest(UUID memberId, String decision) {
}
