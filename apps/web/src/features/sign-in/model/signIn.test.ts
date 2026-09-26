import { describe, expect, it } from 'vitest';

import { codeComplete, deviceId, issueWith } from './signIn';

/**
 * What the sign-in form judges for itself, and nothing more.
 *
 * Every rule here exists because the server cannot say it. A wrong, expired, spent or never-requested code all answer one
 * refusal - deliberately, so nobody can use the endpoint to ask whether a number has a live challenge - so a six-digit
 * field is something this screen has to know if it wants to avoid a request that cannot succeed. The phone number, by
 * contrast, is only checked for being present: what a number looks like is a country's convention, and the API stores it
 * as written.
 */
describe('the sign-in form', () => {
  it('accepts six digits and refuses everything else it could be handed', () => {
    expect(codeComplete('481516')).toBe(true);
    // Five digits, seven, and a letter: all of them are things a person can type into a six-character field.
    expect(codeComplete('48151')).toBe(false);
    expect(codeComplete('4815162')).toBe(false);
    expect(codeComplete('48151a')).toBe(false);
  });

  it('refuses a number that is only whitespace, because the API would refuse it too', () => {
    expect(issueWith('phone', '   ')).toBe('phoneNumber');
    expect(issueWith('phone', '')).toBe('phoneNumber');
    expect(issueWith('phone', '+91 98123 45678')).toBeNull();
  });

  it('refuses a code that is not a code, and takes the trimmed one', () => {
    expect(issueWith('code', '  48151 ')).toBe('code');
    expect(issueWith('code', ' 481516 ')).toBeNull();
  });

  it('names one device, because the server records it and checks nothing about it', () => {
    expect(deviceId()).toBe('web');
  });
});
