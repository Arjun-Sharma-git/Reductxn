/**
 * An instant, as a `datetime-local` input needs it, and back.
 *
 * `datetime-local` has no timezone: it is a wall clock in the reader's zone, which is exactly what a member means
 * when they say "this happened at 14:30". The ledger stores instants in UTC (`occurredAt` is an ISO instant on the
 * wire), so the conversion happens here, at the edge, and nowhere else - a form that sent the local string
 * unconverted would record an instant hours out, and one that stored the UTC string would show a member a time
 * that is not theirs.
 *
 * Seconds are not represented in the control, so an instant that has them loses them when it is edited. That is
 * the input's precision rather than a rounding this code does, and it is stated here because it is the kind of
 * detail that looks like a bug later.
 */

export function toLocalInput(instant: string): string {
  const at = new Date(instant);

  if (Number.isNaN(at.getTime())) {
    return '';
  }

  const pad = (value: number) => value.toString().padStart(2, '0');

  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/** The wall clock the member typed, as the instant the ledger records. */
export function fromLocalInput(local: string): string {
  const at = new Date(local);

  return Number.isNaN(at.getTime()) ? '' : at.toISOString();
}
