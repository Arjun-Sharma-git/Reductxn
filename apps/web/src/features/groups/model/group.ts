/**
 * What the create-group form judges for itself, before a request is worth sending.
 *
 * **The name is checked against the contract's own `maxLength`.** The server refuses a longer one with
 * `api.group_name_too_long`, and a form that let somebody type it would be a form that fails after they have chosen a
 * currency - the kind of refusal that reads as "the app is broken" rather than "that name is too long". The number is here
 * rather than fetched, because the contract is where it comes from and a client that read it at runtime would be reading
 * the schema it was built against (RULE C1).
 *
 * **The currency is not validated here.** It comes from a `<select>` of what `GET /currencies` answered, so a code the
 * catalogue does not hold cannot be chosen - and a rule about which codes exist is reference data the server owns. The
 * server still checks it, which is the point: the client's certainty is a convenience, not a permission.
 */

/** The contract's `maxLength` for a group's name, and the column's constraint. */
export const NAME_LIMIT = 120;

export type GroupIssue = 'name' | 'currency' | null;

/** What is wrong with the form's input, or null when it can be sent. */
export function issueWith(name: string, currency: string): GroupIssue {
  if (name.trim() === '' || name.trim().length > NAME_LIMIT) {
    return 'name';
  }

  return currency === '' ? 'currency' : null;
}

/** The name as it will be sent: trimmed, because a group called " Flat 3B " is a group nobody can search for. */
export function nameFor(body: string): string {
  return body.trim();
}
