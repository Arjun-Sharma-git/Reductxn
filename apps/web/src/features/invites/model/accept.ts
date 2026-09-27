/**
 * The one thing the acceptance form has to get right before a request is worth sending: the name.
 *
 * **It used to mint an identity, and the sign-in slice is what deleted it.** The operation's body carried a `userId`,
 * because there was no sign-in to ask and a person arriving from a link does not have a user id to type - so this module
 * generated one and the screen said so plainly. The contract's own description promised that "when sign-in exists, this
 * body disappears and the acceptance becomes about the caller" (ADR 0047), and it now does: the joiner is the session,
 * so the form asks for a name and nothing else, and `AcceptInvite` carries only what the group will call them.
 */

export type AcceptanceDraft = {
  readonly displayName: string;
};

export type AcceptanceIssue = 'displayName';

/** What is wrong with the draft, or null when it can be sent. */
export function issueWith(draft: AcceptanceDraft): AcceptanceIssue | null {
  return draft.displayName.trim() === '' ? 'displayName' : null;
}

/** The body the contract asks for: the name the group will use, trimmed. Who is joining is the session's business. */
export function acceptanceOf(draft: AcceptanceDraft): { displayName: string } {
  return { displayName: draft.displayName.trim() };
}
