/**
 * The two fields the acceptance form has to get right before a request is worth sending.
 *
 * **The id is generated here, and that is the identity gap made usable rather than hidden.** The operation's body says
 * who is joining because there is no sign-in to ask - and a person joining a group from a link does not have a user id
 * to type, so asking them for one would be asking for something that does not exist. This module therefore mints one per
 * acceptance and the screen says so plainly: in this build, the id *is* the person, and the page that made it is the
 * only place it is ever shown.
 *
 * When sign-in exists, this function loses half of itself: the id comes from the session and only the name is left to
 * validate, which is what the contract's own description anticipates.
 */

export type AcceptanceDraft = {
  readonly displayName: string;
};

export type AcceptanceIssue = 'displayName';

/** A new identity for somebody joining. `crypto.randomUUID` is available wherever this app runs. */
export function newMemberId(): string {
  return crypto.randomUUID();
}

/** What is wrong with the draft, or null when it can be sent. */
export function issueWith(draft: AcceptanceDraft): AcceptanceIssue | null {
  return draft.displayName.trim() === '' ? 'displayName' : null;
}

/** The body the contract asks for: the name the group will use, and the id this page minted. */
export function acceptanceOf(draft: AcceptanceDraft, memberId: string): { userId: string; displayName: string } {
  return { userId: memberId, displayName: draft.displayName.trim() };
}
