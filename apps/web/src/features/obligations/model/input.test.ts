import { describe, expect, it } from 'vitest';

import { ApiProblemError } from '@/shared/api/client';
import { money } from '@/shared/money';

import {
  amountOf,
  emptyForm,
  localRefusals,
  refusalFor,
  refusalsOf,
  worthRetrying,
  type RecordFormValues,
} from './input';

/**
 * The form's rules, without a browser.
 *
 * These are the assertions that would otherwise be made through a UI where they cost seconds each: what a typed
 * amount becomes, which control a refusal is attached to, and whether a failure is worth repeating. The last one
 * is the contract's own 422-versus-503 distinction, and it is the difference between a member pressing a button
 * that will work and one that never will.
 */

const CATALOG = new Map([
  ['INR', { code: 'INR', minorUnitExponent: 2 }],
  ['JPY', { code: 'JPY', minorUnitExponent: 0 }],
]);

const form = (overrides: Partial<RecordFormValues> = {}): RecordFormValues => ({
  ...emptyForm(),
  debtorId: '11111111-1111-4111-8111-111111111111',
  creditorId: '22222222-2222-4222-8222-222222222222',
  amountText: '1250.00',
  currency: 'INR',
  memo: 'Dinner',
  ...overrides,
});

const problem = (code: string, status: number): ApiProblemError =>
  new ApiProblemError({ code, status, params: {}, title: code });

describe('the typed amount', () => {
  it('becomes integer minor units for the currency’s own exponent', () => {
    expect(amountOf('12,34,567.89', 'INR', CATALOG, 'en-IN')).toEqual({
      ok: true,
      value: money(123_456_789, 'INR'),
    });
    // Yen take no decimals, so the exponent comes from the catalogue rather than from a constant.
    expect(amountOf('123457', 'JPY', CATALOG, 'en-IN')).toEqual({ ok: true, value: money(123_457, 'JPY') });
  });

  it('refuses over-precise input rather than truncating it', () => {
    const refused = amountOf('1.239', 'INR', CATALOG, 'en-US');

    expect(refused).toMatchObject({ ok: false, code: 'amount.too_many_decimals', params: { decimals: 3 } });
  });

  it('refuses a currency the catalogue does not hold', () => {
    expect(amountOf('10', 'XYZ', CATALOG, 'en-US')).toMatchObject({
      ok: false,
      code: 'api.obligation_unknown_currency',
    });
  });

  it('refuses zero, which is not an amount anybody owes', () => {
    expect(amountOf('0', 'INR', CATALOG, 'en-US')).toMatchObject({
      ok: false,
      code: 'api.obligation_amount_not_positive',
    });
  });
});

describe('what can be decided locally', () => {
  it('accepts a complete form', () => {
    expect(localRefusals(form(), CATALOG, 'en-US')).toEqual([]);
  });

  it('refuses the same member on both sides, with the server’s own code', () => {
    const refusals = localRefusals(
      form({ creditorId: '11111111-1111-4111-8111-111111111111' }),
      CATALOG,
      'en-US',
    );

    // The code the server answers with, so the member reads one sentence for one rule - and the message is
    // attached to the creditor, because that is the field a person changes.
    expect(refusals).toEqual([{ field: 'creditorId', code: 'api.obligation_same_party' }]);
  });

  it('refuses a missing member, a missing reason and an unreadable instant', () => {
    expect(refusalFor(localRefusals(form({ debtorId: '' }), CATALOG, 'en-US'), 'debtorId')?.code).toBe(
      'api.obligation_request_incomplete',
    );
    expect(refusalFor(localRefusals(form({ memo: '   ' }), CATALOG, 'en-US'), 'memo')?.code).toBe(
      'api.obligation_request_incomplete',
    );
    expect(
      refusalFor(localRefusals(form({ occurredAt: 'not a time' }), CATALOG, 'en-US'), 'occurredAt')?.code,
    ).toBe('form.occurred_at_invalid');
  });
});

describe('what the server says, as fields to fix', () => {
  it('puts a rate failure on the currency control', () => {
    expect(refusalsOf(problem('fx.rate_stale', 422))).toEqual([
      { field: 'settleInCurrency', code: 'fx.rate_stale', params: {} },
    ]);
    expect(refusalsOf(problem('fx.rate_unavailable', 503))[0]?.field).toBe('settleInCurrency');
  });

  it('puts an amount refusal on the amount control', () => {
    expect(refusalsOf(problem('api.obligation_amount_not_positive', 422))[0]?.field).toBe('amountText');
  });

  it('falls back to the form for a code it has no field for', () => {
    // Never hidden: a member looking at a button that did nothing is worse than a message they have to read.
    expect(refusalsOf(problem('api.request_incomplete', 422))[0]?.field).toBe('form');
    expect(refusalsOf(problem('settlement.run_rejected', 409))[0]?.field).toBe('form');
  });

  it('treats an unreachable server as a problem, not as a crash', () => {
    expect(refusalsOf(new TypeError('Failed to fetch'))[0]).toMatchObject({ field: 'form', code: 'client.network' });
  });
});

describe('whether to try again', () => {
  it('is yes for a 503 and for a network failure', () => {
    expect(worthRetrying(problem('fx.rate_unavailable', 503))).toBe(true);
    expect(worthRetrying(new TypeError('Failed to fetch'))).toBe(true);
  });

  it('is no for a 422, because the answer would be the same', () => {
    expect(worthRetrying(problem('fx.rate_stale', 422))).toBe(false);
    expect(worthRetrying(problem('api.obligation_same_party', 422))).toBe(false);
  });
});
