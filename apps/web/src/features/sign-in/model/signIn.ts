/**
 * What the sign-in form has to get right before a request is worth sending.
 *
 * **The code is six digits, and that is checked here rather than assumed.** The server answers one refusal for a wrong,
 * expired, spent or never-requested code - deliberately, so an attacker cannot tell whether a number has a live challenge
 * - which means a client that sent something which could not be a code would get "that code is not one this deployment
 * accepts" and be no closer to knowing why. A six-digit field is a thing this screen knows and the server will not say.
 *
 * **The phone number is only checked for being there.** What a phone number looks like belongs to a country and a
 * convention, not to this form: the number is stored and compared exactly as it was written, so normalising it here would
 * be this client deciding that two spellings of one number are the same when the API has already decided they are not.
 */

export type SignInStep = 'phone' | 'code';

export type SignInIssue = 'phoneNumber' | 'code' | null;

/** Digits only, six of them, which is what a code is. */
export function codeComplete(code: string): boolean {
  return /^[0-9]{6}$/.test(code);
}

/** What is wrong with the step's input, or null when it can be sent. */
export function issueWith(step: SignInStep, value: string): SignInIssue {
  if (step === 'phone') {
    return value.trim() === '' ? 'phoneNumber' : null;
  }

  return codeComplete(value.trim()) ? null : 'code';
}

/** The device this client signs in as. A label, not a credential: the server records it and checks nothing about it. */
export function deviceId(): string {
  return 'web';
}
