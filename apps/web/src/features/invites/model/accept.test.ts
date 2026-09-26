import { describe, expect, it } from 'vitest';

import { acceptanceOf, issueWith, newMemberId } from './accept';

/**
 * The acceptance's own judgements, which are about the gap rather than about the invite.
 *
 * There is no sign-in in this build, so the page mints the id that stands for the person joining. These tests pin down
 * the two things that must hold for that to be honest: an id that is a real id (the server refuses anything else, and a
 * uuid that is not a uuid would be a 400 for a reason the screen could not show), and a name that is not just
 * whitespace, because the server refuses a blank one and the group would otherwise record an empty member.
 */
describe('the acceptance form', () => {
  it('mints an id the API will accept', () => {
    const id = newMemberId();

    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(newMemberId()).not.toBe(id);
  });

  it('refuses a name that is only whitespace, because the group would record nothing', () => {
    expect(issueWith({ displayName: '   ' })).toBe('displayName');
    expect(issueWith({ displayName: '' })).toBe('displayName');
    expect(issueWith({ displayName: 'Alice' })).toBeNull();
  });

  it('sends the trimmed name and the id it minted', () => {
    // Trimmed because a name with trailing spaces is a name the group did not choose, and the rename that would fix it
    // belongs to an admin operation this build does not have.
    expect(acceptanceOf({ displayName: '  Alice Rao  ' }, 'an-id')).toEqual({
      userId: 'an-id',
      displayName: 'Alice Rao',
    });
  });
});
