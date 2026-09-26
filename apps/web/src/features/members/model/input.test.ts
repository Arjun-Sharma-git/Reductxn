import { describe, expect, it } from 'vitest';

import { addMemberBody, emptyForm, localRefusals, refusalFor } from './input';

/**
 * What a member typed, as a request.
 *
 * Every expectation here is about a rule that also exists on the server, which is the point: the local check is a
 * courtesy that saves a round trip, and it uses the server's codes so that a rule reads the same wherever it was
 * caught. The one thing this file deliberately does *not* test is the role vocabulary - the API is the authority on
 * which words it stores, and a client that enumerated them would refuse a role a later build learned.
 */
const ASHA = '11111111-1111-4111-8111-111111111111';

describe('addMemberBody', () => {
  it('sends what the member typed, trimmed', () => {
    const result = addMemberBody({ userId: ` ${ASHA} `, displayName: '  Asha  ', role: 'admin' });

    expect(result.ok).toBe(true);
    expect(result.ok && result.value).toEqual({ userId: ASHA, displayName: 'Asha', role: 'admin' });
  });

  it('starts a member on the ordinary role rather than on no choice at all', () => {
    // The contract's absent `role` means `member`, and this form always sends a word: a request that does not say
    // would be a control the member cannot see.
    expect(emptyForm().role).toBe('member');
    expect(addMemberBody({ ...emptyForm(), userId: ASHA, displayName: 'Asha' }).ok).toBe(true);
  });

  it('refuses a user id that is not one, with the code the server would answer', () => {
    // The ordinary mistake this catches is a name typed into the field that wants an identifier.
    const result = addMemberBody({ ...emptyForm(), userId: 'Asha', displayName: 'Asha' });

    expect(result.ok).toBe(false);
    expect(!result.ok && result.code).toBe('api.request_incomplete');
    expect(refusalFor(localRefusals({ ...emptyForm(), userId: 'Asha', displayName: 'Asha' }), 'userId')).toBeDefined();
  });

  it('refuses an empty name, and the form knows which control to blame', () => {
    const values = { ...emptyForm(), userId: ASHA, displayName: '   ' };

    expect(localRefusals(values).length).toBe(1);
    expect(refusalFor(localRefusals(values), 'displayName')?.code).toBe('api.request_incomplete');
  });

  it('refuses a name past the contract’s length with the server’s own code', () => {
    // Refused rather than truncated, at both ends, with one code: a name cut to length would be one the group did not
    // choose, and a rule that reads differently depending on who caught it is two rules.
    const values = { ...emptyForm(), userId: ASHA, displayName: 'A'.repeat(121) };

    expect(refusalFor(localRefusals(values), 'displayName')?.code).toBe('api.member_name_too_long');
  });

  it('accepts a name exactly at the limit, because the server stores exactly that', () => {
    const values = { ...emptyForm(), userId: ASHA, displayName: 'A'.repeat(120) };

    expect(localRefusals(values)).toEqual([]);
    expect(addMemberBody(values).ok).toBe(true);
  });
});
