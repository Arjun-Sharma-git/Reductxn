package io.reductxn.api.port.in;

/**
 * The use case: make a code that lets somebody join a group.
 *
 * <p><strong>No {@code Result}, because nothing here can fail.</strong> The boundary refuses a malformed group id before
 * this is called, and the store cannot refuse: a code collision is settled by the primary key and retried, and a group
 * that does not exist is not a refusal for the reason every other write in this module gives - groups are not tables in
 * this build, so "no such group" is a question this deployment cannot answer (ADR 0035). An invitation to a group
 * nobody has joined yet is a perfectly ordinary invitation.
 *
 * <p><strong>It exists at all because a read with no writer is a dead end, and here the writer comes first.</strong>
 * `POST /groups/{groupId}/invites` has been in the contract since the obligations slice with nothing behind it, and the
 * table it needs did not exist until this slice. The acceptance endpoint is its other half, and the two share one rule:
 * a code is usable until it expires or runs out of uses, and the only way anybody gets one is through this operation.
 *
 * <p>The window and the limit are this use case's decision rather than a caller's, because the contract gives the
 * operation no request body to put them in. That is stated in the contract's description with the values, so a reader
 * knows what they are getting rather than inferring it from behaviour.
 */
public interface CreateInvite {

    /** Records an invitation to {@code invite}'s group and answers with the code it can be spent by. */
    CreatedInvite create(NewInvite invite);
}
