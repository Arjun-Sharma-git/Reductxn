import { refuse, type Result } from '@/shared/lib/result';

import { digitsOf, group, type Digits } from './digits';
import type { Money } from './money';

/**
 * Formatting money without ever making the amount a floating-point number.
 *
 * This is the implementation of `docs/specs/i18n.md` section 4, and the rule it satisfies is RULE F11: **never**
 * `amount.toLocaleString()` on `amountMinor / 100`. That version passes for most values and is wrong for large
 * ones - exactly the failure class P1 exists to prevent, and one that would not be noticed until a group settled
 * a cycle that ended in the wrong digits.
 *
 * The approach is the one the spec prescribes, and it is a substitution:
 *
 *   1. the *digits* come from the integer, by slicing its string (`digitsOf`);
 *   2. the *pattern* - where the currency symbol goes, which decimal separator, which group separator - comes
 *      from `Intl.NumberFormat.formatToParts`, probed with `0`, a value that is exact in any representation;
 *   3. the *group sizes* come from a `BigInt` probe, which is exact at any length, because the grouping is a
 *      property of the locale's shape rather than of our value;
 *   4. the digits are mapped to the locale's numbering system, again by probe, so a locale that writes
 *      Arabic-Indic digits needs no table here.
 *
 * Nothing rounds, divides or multiplies. The output for `123456789` minor units of INR is the same integer it
 * always was, wearing the locale's punctuation - which is what the property test asserts.
 */

const PROBE_INTEGER = '1234567890123456';
const DIGITS = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'] as const;

type Pattern = {
  /** What comes before the whole units: the symbol in `en`, and any literal the locale puts with it. */
  readonly before: string;
  /** What comes after the fraction. */
  readonly after: string;
  readonly decimal: string;
  readonly group: string;
  readonly sizes: readonly number[];
  readonly digits: readonly string[];
};

/* Locale data is cached per (locale, currency, exponent): constructing Intl objects is the expensive part, and
   a settlement table formats hundreds of amounts per render. The key is bounded by the deployment's own
   currency catalogue, so this cannot grow without a deployment change. */
const patterns = new Map<string, Pattern>();

function patternFor(locale: string, currency: string, exponent: number): Pattern {
  const key = `${locale}|${currency}|${exponent}`;
  const cached = patterns.get(key);

  if (cached !== undefined) {
    return cached;
  }

  const formatter = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: exponent,
    maximumFractionDigits: exponent,
  });

  const parts = formatter.formatToParts(0);

  // Everything before the probe's integer part is the prefix and everything after its fraction is the suffix.
  // The probe's own digits, decimal separator and group separators are the things being replaced.
  const integerAt = parts.findIndex((part) => part.type === 'integer');
  const fractionAt = parts.findIndex((part) => part.type === 'fraction');

  const before = integerAt < 0 ? [] : parts.slice(0, integerAt);
  const after = fractionAt < 0 ? [] : parts.slice(fractionAt + 1);

  const shape = new Intl.NumberFormat(locale, {
    useGrouping: true,
    maximumFractionDigits: 0,
  }).formatToParts(BigInt(PROBE_INTEGER));

  // The probe is long enough to show both group sizes. The last chunk is the primary size (3 in every Latin-script
  // locale) and the one before it is the size that repeats - 3 for thousands grouping, 2 for the lakh/crore
  // grouping `en-IN` writes. Taking the *first* chunk instead was the bug the worked-examples test caught: in
  // `en-IN` the leading chunk has a variable length, so the repeats came out as ones (`1,2,3,4,567`).
  const chunks = shape.filter((part) => part.type === 'integer').map((part) => part.value.length);
  const primary = chunks.at(-1) ?? 3;
  const sizes = [primary, chunks.at(-2) ?? primary];

  const digits = DIGITS.map(
    (digit) =>
      new Intl.NumberFormat(locale, { useGrouping: false })
        .formatToParts(BigInt(digit))
        .find((part) => part.type === 'integer')?.value ?? digit,
  );

  const pattern: Pattern = {
    before: before.map((part) => part.value).join(''),
    after: after.map((part) => part.value).join(''),
    decimal: parts.find((part) => part.type === 'decimal')?.value ?? '.',
    // From the *shape* probe, not from the value probe: `formatToParts(0)` has no group separator in it, because
    // zero does not need one. Reading it from there produced ungrouped amounts for every locale, which is the
    // defect the worked-examples test caught.
    group: shape.find((part) => part.type === 'group')?.value ?? '',
    sizes,
    digits,
  };

  patterns.set(key, pattern);

  return pattern;
}

/**
 * Digits mapped to the locale's numbering system, so a locale that does not write Latin digits still works.
 *
 * Only *digits* are mapped, and the check is explicit rather than "look it up in the table": `Number(' ')` is
 * `0`, so mapping every character of a grouped digit string replaced the locale's grouping separator with a zero
 * (`1 234,56` became `10234,56`). The property test found that, which is what it is for.
 */
function localize(digits: string, pattern: Pattern): string {
  return [...digits]
    .map((character) => (/\d/.test(character) ? (pattern.digits[Number(character)] ?? character) : character))
    .join('');
}

/**
 * An amount as this locale writes it.
 *
 * @param exponent the currency's minor-unit exponent, from `/currencies` rather than a constant: an exponent
 *                 compiled into a client is one that cannot be corrected without a release (P1)
 */
export function formatMoney(value: Money, locale: string, exponent: number): string {
  const pattern = patternFor(locale, value.currency, exponent);
  const digits: Digits = digitsOf(value.amountMinor, exponent);

  const units =
    pattern.group !== '' ? group(digits.units, pattern.group, pattern.sizes) : digits.units;

  const number =
    digits.fraction === ''
      ? localize(units, pattern)
      : `${localize(units, pattern)}${pattern.decimal}${localize(digits.fraction, pattern)}`;

  return `${pattern.before}${number}${pattern.after}`;
}

/**
 * An amount, or the refusal that says why it cannot be rendered yet.
 *
 * The failing case is a currency the client's copy of the catalogue does not know. That is a genuine state - the
 * catalogue is fetched, and a stale copy is possible - so it is a value the screen renders as "not yet
 * available" rather than a crash in the middle of a table.
 */
export function formatMoneyOrRefusal(
  value: Money,
  locale: string,
  catalog: ReadonlyMap<string, { readonly minorUnitExponent: number }>,
): Result<string> {
  const info = catalog.get(value.currency);

  if (info === undefined) {
    return refuse('amount.unknown_currency', { currency: value.currency });
  }

  return { ok: true, value: formatMoney(value, locale, info.minorUnitExponent) };
}
