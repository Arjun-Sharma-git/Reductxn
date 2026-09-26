/**
 * An amount, as the digits it actually is.
 *
 * `123456789` minor units in a two-exponent currency is `1234567` and `89` - and those two strings are derived
 * from the integer with `toString` and slicing, never by dividing. That is the whole P1 argument in the client:
 * the moment an amount becomes `1234567.89` as a JavaScript number, the digits are no longer a fact, they are a
 * floating-point approximation that happens to print correctly for the values somebody thought to test.
 */

export type Digits = {
  /** The whole units, without grouping separators. `0` when the amount is smaller than one unit. */
  readonly units: string;
  /** Exactly `exponent` fractional digits, zero-padded. Empty when the currency has no minor unit. */
  readonly fraction: string;
};

export function digitsOf(amountMinor: number, exponent: number): Digits {
  if (!Number.isSafeInteger(amountMinor) || amountMinor < 0) {
    throw new RangeError(`digitsOf expects a non-negative safe integer, received ${amountMinor}`);
  }

  if (!Number.isInteger(exponent) || exponent < 0 || exponent > 8) {
    throw new RangeError(`digitsOf expects an exponent between 0 and 8, received ${exponent}`);
  }

  const text = amountMinor.toString();

  if (exponent === 0) {
    return { units: text, fraction: '' };
  }

  const padded = text.padStart(exponent + 1, '0');
  const split = padded.length - exponent;

  return { units: padded.slice(0, split), fraction: padded.slice(split) };
}

/**
 * The digits of an amount, and the grouping separators the locale asks for.
 *
 * Group sizes come from a probe formatted as a `BigInt`, which is exact at any length. Formatting the amount
 * itself would not be: it is the value's own magnitude that must not become a double, and a probe is only ever
 * used for its *shape* - where the separators go - never for its value.
 *
 * @param units the whole-unit digits, ungrouped
 * @param sizes the group sizes the locale uses, largest group first as Intl reports them (for instance `[3]`,
 *              or `[3, 2]` for the lakh/crore grouping `en-IN` uses)
 */
export function group(units: string, separator: string, sizes: readonly number[]): string {
  if (units.length <= (sizes[0] ?? 0)) {
    return units;
  }

  const primary = sizes[0] ?? 3;
  const secondary = sizes[1] ?? primary;

  const groups: string[] = [];
  let rest = units;

  groups.unshift(rest.slice(-primary));
  rest = rest.slice(0, -primary);

  while (rest.length > 0) {
    groups.unshift(rest.slice(-secondary));
    rest = rest.slice(0, -secondary);
  }

  return groups.join(separator);
}
