import { describe, expect, it } from 'vitest';

import { acceptanceOf, issueWith } from './accept';

/**
 * The acceptance's own judgement, which is about a name now rather than about an identity.
 *
 * These tests used to pin the id this page minted - a real uuid, because the server refused anything else, and the way
 * this build made a person out of a link. That id is gone with the body field it was sent in (ADR 0047): the joiner is
 * the session, so what is left for this module to get right is a name the group can record, because the server refuses a
 * blank one and the group would otherwise have a member it cannot name.
 */
describe('the acceptance form', () => {
  it('refuses a name that is only whitespace, because the group would record nothing', () => {
    expect(issueWith({ displayName: '   ' })).toBe('displayName');
    expect(issueWith({ displayName: '' })).toBe('displayName');
    expect(issueWith({ displayName: 'Alice' })).toBeNull();
  });

  it('sends the trimmed name and nothing else', () => {
    // Trimmed because a name with trailing spaces is a name the group did not choose, and the rename that would fix it
    // belongs to an admin operation this build does not have. Nothing else is sent: `AcceptInvite` has one field, and
    // who is joining is the session's business rather than the form's.
    expect(acceptanceOf({ displayName: '  Alice Rao  ' })).toEqual({ displayName: 'Alice Rao' });
  });
});
