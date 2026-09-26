import { describe, expect, it } from 'vitest';

import type { TaxRule } from '@/entities/tax';

import { isInverted, ruleParams } from './tax';

/**
 * The two judgements the tax screen makes for itself.
 *
 * The parameters are the interesting one. The client does not implement the tax engine, so it must not pretend to know
 * what `rateBps` is a percentage of - what it can do is show a reader every key and value the rule carries, which is
 * what these tests pin down: a scalar shown plainly, a nested value shown rather than summarized away.
 */
const RULE = (params: Record<string, unknown>): TaxRule => ({
  id: '9b2f1c4e-6a3d-4f2b-8c1e-2d4a6b8c0e12',
  jurisdiction: 'IN',
  currency: 'INR',
  kind: 'flat',
  params,
  effectiveFrom: '2020-01-01T00:00:00Z',
  effectiveTo: null,
  sourceRef: 'FY2020 s.7(3)',
});

describe('ruleParams', () => {
  it('shows a flat charge as the pair the rule carries', () => {
    expect(ruleParams(RULE({ perTransferMinor: 250 }))).toEqual([{ key: 'perTransferMinor', value: '250' }]);
  });

  it('shows every key of an ad valorem rule, bounds included', () => {
    // Nothing is dropped for being optional: a bound a reader cannot see is a bound they will not check.
    expect(ruleParams(RULE({ rateBps: 150, minMinor: 0, maxMinor: 50_000 }))).toEqual([
      { key: 'rateBps', value: '150' },
      { key: 'minMinor', value: '0' },
      { key: 'maxMinor', value: '50000' },
    ]);
  });

  it('shows a nested value rather than hiding it behind a placeholder', () => {
    const tiers = [{ uptoMinor: 10_000, rateBps: 100 }];

    expect(ruleParams(RULE({ tiers }))).toEqual([{ key: 'tiers', value: JSON.stringify(tiers) }]);
  });

  it('answers an empty list for a rule with no parameters, rather than inventing one', () => {
    expect(ruleParams(RULE({}))).toEqual([]);
  });
});

describe('isInverted', () => {
  it('is true only when the reader has asked for a range that cannot contain anything', () => {
    expect(isInverted('2026-09-30', '2026-09-01')).toBe(true);
    expect(isInverted('2026-09-01', '2026-09-30')).toBe(false);
    // One bound is an open range rather than a backwards one, and neither bound is the whole history.
    expect(isInverted(undefined, '2026-09-30')).toBe(false);
    expect(isInverted('2026-09-01', undefined)).toBe(false);
    expect(isInverted(undefined, undefined)).toBe(false);
  });
});
