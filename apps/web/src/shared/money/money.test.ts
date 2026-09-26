import { describe, expect, it } from 'vitest';

import { digitsOf } from './digits';
import { formatMoney, formatRate } from './format';
import { money } from './money';
import { parseAmount } from './parse';

/**
 * The two properties the spec says matter most (`docs/specs/i18n.md` section 10): **I4**, that an amount survives
 * a round trip through the formatter and back, and **I5**, that formatting never changes the digits.
 *
 * **Why a generator loop rather than a property-testing library.** There is no jqwik here and no equivalent of
 * `Double`-free generators in TypeScript: a small deterministic generator seeded from a fixed constant runs the
 * same cases on every machine, which is what makes a failure reproducible. The values are chosen to include the
 * ones a float-based formatter gets wrong - larger than 2^53 in minor units is impossible by the contract, but
 * 10^15 is not, and `123456789` is the spec's own worked example.
 *
 * The expected strings are *measured* from `Intl`, not invented: the grouping, the separators and the placement
 * of the symbol are the locale's, which is the whole point - this code contributes the digits and nothing else.
 */

const LOCALES = ['en-IN', 'en-US', 'ru', 'ja'] as const;

/** A deterministic generator: the same cases everywhere, so a failure is reproducible without a seed file. */
function* amounts(): Generator<number> {
  const seeds = [
    0, 1, 5, 99, 100, 101, 999, 1234, 12_345, 123_456, 1_234_567, 12_345_678, 123_456_789, 9_007_199_254,
    1_000_000_000_000, 999_999_999_999_999,
  ];

  yield* seeds;

  let state = 42;

  for (let index = 0; index < 400; index += 1) {
    state = (state * 1_103_515_245 + 12_345) % 2_147_483_647;

    yield state * 1000 + (index % 100);
  }
}

describe('money formatting', () => {
  it('I4: an amount survives formatting and parsing, in every launch locale', () => {
    for (const locale of LOCALES) {
      for (const amountMinor of amounts()) {
        const formatted = formatMoney(money(amountMinor, 'INR'), locale, 2);
        const parsed = parseAmount(formatted, locale, 2);

        expect(parsed.ok ? parsed.value : `refused:${parsed.code}`).toBe(amountMinor);
      }
    }
  });

  it('I5: formatting never changes the digits', () => {
    for (const locale of LOCALES) {
      for (const amountMinor of amounts()) {
        const formatted = formatMoney(money(amountMinor, 'INR'), locale, 2);
        const digits = digitsOf(amountMinor, 2);

        // Every digit of the exact value appears, in order, in the formatted output - grouping and the currency
        // symbol are the locale's, the digits are ours.
        const digitsOnly = [...formatted].filter((character) => /\d/.test(character)).join('');

        expect({ locale, amountMinor, digitsOnly, formatted }).toEqual({
          locale,
          amountMinor,
          formatted,
          digitsOnly: `${digits.units}${digits.fraction}`,
        });
      }
    }
  });

  it('writes the spec’s worked examples the way the spec says they read', () => {
    // 123,456,789 minor units of INR is 12,34,567.89 at exponent 2.
    const value = money(123_456_789, 'INR');

    expect(formatMoney(value, 'en-IN', 2)).toBe('₹12,34,567.89');
    expect(formatMoney(value, 'en-US', 2)).toBe('₹1,234,567.89');
    expect(formatMoney(value, 'ja', 2)).toBe('₹1,234,567.89');
  });

  it('writes a currency with no minor unit without inventing a decimal separator', () => {
    // 123,457 yen: exponent 0, so nothing is divided and no decimal point appears.
    const yen = money(123_457, 'JPY');

    expect(formatMoney(yen, 'ja', 0)).toBe('￥123,457');
    // `en-IN` writes the yen as `JP¥` and groups a six-digit amount the way it groups lakhs - which is the
    // locale's business, not ours: the digits are the same integer either way.
    expect(formatMoney(yen, 'en-IN', 0)).toBe('JP¥1,23,457');
  });

  it('writes a scaled rate the same way, because a rate is a scaled integer too (P12)', () => {
    // 55904 at scale 5 is 0.55904 - the ADR 0013 example, and the digits come from the integer here exactly as
    // they do for an amount. A rate formatted with `rateScaled / 10 ** rateScale` would be the same mistake as
    // dividing an amount by a hundred, one currency over.
    expect(formatRate(55_904, 5, 'en-IN')).toBe('0.55904');
    expect(formatRate(55_904, 5, 'ru')).toBe('0,55904');

    // A rate above one, grouped: 123456789 at scale 5 is 1234.56789.
    expect(formatRate(123_456_789, 5, 'en-IN')).toBe('1,234.56789');
    expect(formatRate(123_456_789, 5, 'ru')).toBe('1\u00A0234,56789');
  });
});

describe('amount input', () => {
  it('reads the spec’s cases', () => {
    expect(parseAmount('12,34,567.89', 'en-IN', 2)).toEqual({ ok: true, value: 123_456_789 });
    expect(parseAmount('123 457', 'ja', 0)).toEqual({ ok: true, value: 123_457 });
    expect(parseAmount('1.2.3', 'en-US', 2)).toMatchObject({ ok: false, code: 'amount.unparseable' });
    expect(parseAmount('1.2345678', 'en-IN', 2)).toMatchObject({
      ok: false,
      code: 'amount.too_many_decimals',
    });
  });

  it('refuses over-precise input rather than truncating it', () => {
    // The value the user typed is 1.239; truncating would record 1.23 - a silent loss of money, which is the one
    // failure this repository is built to make impossible.
    const refused = parseAmount('1.239', 'en-US', 2);

    expect(refused).toMatchObject({ ok: false, code: 'amount.too_many_decimals', params: { decimals: 3 } });
  });

  it('refuses a signed amount, because a magnitude has no sign (L2)', () => {
    expect(parseAmount('-12', 'en-US', 2)).toMatchObject({ ok: false, code: 'amount.signed' });
  });

  it('accepts a pasted symbol, which is what a user does with a copied amount', () => {
    expect(parseAmount('₹1,234.56', 'en-IN', 2)).toEqual({ ok: true, value: 123_456 });
    expect(parseAmount('INR 1,234.56', 'en-IN', 2)).toEqual({ ok: true, value: 123_456 });
  });

  it('distinguishes an empty field from something that is not a number', () => {
    expect(parseAmount('   ', 'en-IN', 2)).toMatchObject({ ok: false, code: 'amount.empty' });
    expect(parseAmount('not an amount', 'en-IN', 2)).toMatchObject({
      ok: false,
      code: 'amount.unparseable',
    });
  });
});
