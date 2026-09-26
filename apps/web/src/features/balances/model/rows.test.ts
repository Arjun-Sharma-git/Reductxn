import { describe, expect, it } from 'vitest';

import { balanceRows, hasUnnamedRows } from './rows';

/**
 * Joining positions to names, which is this screen's only piece of logic.
 *
 * The cases are the three the screen has to survive: a member the catalogue knows, a member it does not (the
 * catalogue failed, or they left), and the currency clearing account - which is not a member and never will be, so a
 * screen that waited for a name would wait forever.
 */
const ASHA = '11111111-1111-4111-8111-111111111111';
const BHAVESH = '22222222-2222-4222-8222-222222222222';
const CLEARING = '99999999-9999-4999-8999-999999999999';

const MEMBERS = [{ userId: ASHA, displayName: 'Asha Menon', role: 'admin' as const }];

const POSITION = (userId: string, currency: string, amountMinor: number, direction: 'pay' | 'receive' | 'flat') => ({
  userId,
  direction,
  net: { amountMinor, currency },
});

describe('balanceRows', () => {
  it('names a member the catalogue knows, and keeps the server’s order', () => {
    const rows = balanceRows([POSITION(BHAVESH, 'INR', -400, 'pay'), POSITION(ASHA, 'INR', 400, 'receive')], MEMBERS);

    expect(rows.map((row) => row.name)).toEqual([BHAVESH, 'Asha Menon']);
  });

  it('falls back to the identifier rather than inventing a member', () => {
    // A cross-currency group's clearing position: nobody holds it, and the schema cannot label it yet. Showing the
    // identifier is what the members screen does for a missing name, and it is the same honesty (ADR 0039).
    const rows = balanceRows([POSITION(CLEARING, 'JPY', 12_345, 'receive')], MEMBERS);

    expect(rows[0]?.name).toBe(CLEARING);
    expect(hasUnnamedRows(rows)).toBe(true);
  });

  it('says nothing about unnamed rows when every row has a name', () => {
    expect(hasUnnamedRows(balanceRows([POSITION(ASHA, 'INR', 400, 'receive')], MEMBERS))).toBe(false);
  });

  it('keys rows by member and currency, because a member can hold two', () => {
    // The key is what React reconciles a list by: two rows for one member in two currencies would collide on the
    // identifier alone, and a colliding key is how one row's rendering ends up in another's place.
    const rows = balanceRows(
      [POSITION(ASHA, 'INR', 400, 'receive'), POSITION(ASHA, 'JPY', 12_345, 'receive')],
      MEMBERS,
    );

    expect(rows.map((row) => row.key)).toEqual([`${ASHA}-INR`, `${ASHA}-JPY`]);
    expect(new Set(rows.map((row) => row.key)).size).toBe(2);
  });
});
