import { ok, refuse, type Result } from '@/shared/lib/result';

import { MAX_AMOUNT_MINOR } from './money';

/**
 * What a user typed, as integer minor units - or the reason it is not an amount.
 *
 * This is RULE F12, and `docs/specs/i18n.md` section 5 gives it its cases:
 *
 * ```
 * "12,34,567.89"  (en-IN)  ->  123456789
 * "1 234 567,89"  (ru)     ->  123456789
 * "123457"        (ja,JPY) ->  123457
 * "1.2.3"                  ->  refused: amount.unparseable
 * "1.2345678"     (INR)    ->  refused: amount.too_many_decimals
 * ```
 *
 * Two decisions in here are worth more than the code around them.
 *
 * **`Number.parseFloat` is never used**, and neither is any other float path: the digits are collected as a
 * string and turned into an integer once, at the end. `"1234567.89"` goes through `parseFloat` as a value that
 * is already not the value it looks like, and the error is invisible until a ledger disagrees with a bank.
 *
 * **Over-precise input is refused, not truncated.** Dropping the eighth decimal of a rupee is losing a
 * fraction of a paisa, and losing money quietly - however small - is the thing this repository is built to make
 * impossible. The user is told; the value is not silently changed.
 *
 * The locale's separators are read from `Intl` rather than assumed, so the same string means what it means
 * where the user is: `1.234` is one thousand two hundred and thirty-four in `en` and one-and-a-bit in `de`.
 */

/** Whitespace a user may reasonably type as a group separator, including the non-breaking space Intl emits. */
const SPACE_LIKE = /[\s\u00A0\u202F]/g;

/** A separator is one character, but it may be a regex metacharacter (`.` in `de`), so escape it for a class. */
function escapeForClass(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\-]/g, '\\$&');
}

type Separators = { readonly decimal: string; readonly group: string };

const separators = new Map<string, Separators>();

function separatorsFor(locale: string): Separators {
  const cached = separators.get(locale);

  if (cached !== undefined) {
    return cached;
  }

  const parts = new Intl.NumberFormat(locale, {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  }).formatToParts(1234.5);

  const found: Separators = {
    decimal: parts.find((part) => part.type === 'decimal')?.value ?? '.',
    group: parts.find((part) => part.type === 'group')?.value ?? ',',
  };

  separators.set(locale, found);

  return found;
}

/**
 * @param exponent the currency's minor-unit exponent: the number of fractional digits the currency has, and
 *                 therefore the precision limit
 */
export function parseAmount(input: string, locale: string, exponent: number): Result<number> {
  const separators = separatorsFor(locale);

  if (input.trim() === '') {
    return refuse('amount.empty');
  }

  // The sign is checked *before* anything is stripped, because stripping is what would remove it: an unstripped
  // `-12` becomes `12` and the refusal below would never fire. Found by the test that asserts a signed amount is
  // refused, which then passed a minus sign straight through into a positive amount.
  if (/^[-+±]/.test(input.trim())) {
    // L2: an amount is a magnitude and the direction carries the sign. A signed amount would make a debtor and
    // a creditor interchangeable, which is not a thing a ledger can be told.
    return refuse('amount.signed');
  }

  // The locale's own group separator, whitespace of any kind (which is what a person types for a thin space and
  // what Intl itself emits in several locales), and anything that is not a digit or the decimal separator -
  // which is how a pasted "₹1,234.56" or "INR 1,234.56" is accepted. A currency symbol is not part of an amount,
  // and refusing to read one would be pedantry directed at the user.
  const withoutGroups = input
    .replaceAll(separators.group, '')
    .replaceAll(new RegExp(`[^\\d\\s\\u00A0\\u202F${escapeForClass(separators.decimal)}]`, 'gu'), '')
    .replace(SPACE_LIKE, '');

  if (withoutGroups === '') {
    // Something was typed and none of it was a digit - so this is not an amount, rather than an empty one.
    return refuse('amount.unparseable', { input });
  }

  const decimalAt = withoutGroups.indexOf(separators.decimal);

  if (decimalAt !== withoutGroups.lastIndexOf(separators.decimal)) {
    return refuse('amount.unparseable', { input });
  }

  const units = decimalAt < 0 ? withoutGroups : withoutGroups.slice(0, decimalAt);
  const fraction = decimalAt < 0 ? '' : withoutGroups.slice(decimalAt + separators.decimal.length);

  // Both sides may be empty in a partial entry (".5", "5."), and both may be absent digits only by the user's
  // punctuation - so the check is that nothing *other* than a digit survived the separators.
  if (!/^\d*$/.test(units) || !/^\d*$/.test(fraction)) {
    return refuse('amount.unparseable', { input });
  }

  if (fraction.length > exponent) {
    return refuse('amount.too_many_decimals', {
      input,
      exponent,
      decimals: fraction.length,
    });
  }

  const padded = fraction.padEnd(exponent, '0');
  const digits = `${units === '' ? '0' : units}${padded}`;

  if (digits.length > 16) {
    return refuse('amount.above_bound', { input });
  }

  const amountMinor = Number.parseInt(digits, 10);

  if (!Number.isSafeInteger(amountMinor) || amountMinor > MAX_AMOUNT_MINOR) {
    return refuse('amount.above_bound', { input });
  }

  return ok(amountMinor);
}
