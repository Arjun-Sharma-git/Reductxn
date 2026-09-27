package io.reductxn.api.web.dto;

/**
 * The contract's request body for {@code POST /runs/{runId}/decisions}.
 *
 * <p><strong>The body no longer names the member, and the field it lost was the whole reason it existed.</strong>
 * "Approve a computed run" with only a path parameter describes an approval by nobody - a quorum is a count of
 * *members*, so an answer has to name one - and the field added for that named the wrong thing. The answering member
 * is the caller, and since ADR 0053 the session says who that is. There is no capability for answering on another
 * member's behalf, which is why there is no field for it: a request cannot claim somebody else's answer.
 *
 * <p>What it is *not* is the same as an obligation's parties. {@code RecordObligationRequest} still names its
 * {@code debtorId} and its parties, and correctly: a debtor is the debt's party rather than its author, and no
 * session decides who owes whom. An answer has one author, and the caller is it.
 *
 * <p><strong>{@code decision} is the answer, and it is optional because yes is the common one.</strong> Absent means
 * approve - including an absent *body*, now that nothing but this field is in it - which is what the request meant
 * before a refusal was expressible; the mapper applies that default, so the wire format's silence has exactly one
 * interpretation and the use case never sees it. The word itself is the run's rather than this module's -
 * {@code approve} or {@code reject}, as the migration's check constraint writes them - and it is carried through
 * rather than validated here: the row refuses a word that is not in the vocabulary, and a coded refusal from the
 * store is a better answer than a second copy of the list in Java.
 *
 * <p>No bean-validation annotations, for the reason {@code RecordObligationRequest} gives: without a validation
 * provider they would be silently inert, and an annotation that reads like a check but is not one is worse than no
 * annotation. The controller checks what it needs and answers a malformed request with a {@code 400} carrying a
 * code, not with Spring's own error body.
 */
public record DecisionRequest(String decision) {
}
