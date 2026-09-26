import type { Page } from '@playwright/test';

/**
 * The API, stubbed from fixtures that are typed by the contract.
 *
 * `paths` is generated from `contracts/openapi/reductxn-api.yaml`, so a fixture with a field the contract does not
 * have, or a missing required field, fails `pnpm typecheck` rather than passing a test about a response that
 * cannot happen. RULE C3's check that "regenerating produces no diff" is the other half of the same idea.
 */
import type { paths } from '../../src/shared/api/client';

export type NettingPreview =
  paths['/groups/{groupId}/netting/preview']['get']['responses']['200']['content']['application/json'];

export type CurrencyInfo =
  paths['/currencies']['get']['responses']['200']['content']['application/json'][number];

export const CURRENCIES: CurrencyInfo[] = [
  { code: 'INR', minorUnitExponent: 2, isCbdc: false, jurisdiction: 'IN' },
  { code: 'JPY', minorUnitExponent: 0, isCbdc: false, jurisdiction: 'JP' },
];

/** Two currencies, a debt and a claim, and a tax saving in each - the shape a real preview has. */
export const PREVIEW: NettingPreview = {
  currency: 'INR',
  bestCase: {
    positions: [
      { userId: '11111111-1111-4111-8111-111111111111', direction: 'pay', net: { amountMinor: 12_345_678, currency: 'INR' } },
      { userId: '22222222-2222-4222-8222-222222222222', direction: 'receive', net: { amountMinor: 12_345_678, currency: 'INR' } },
      { userId: '33333333-3333-4333-8333-333333333333', direction: 'flat', net: { amountMinor: 0, currency: 'INR' } },
      { userId: '44444444-4444-4444-8444-444444444444', direction: 'pay', net: { amountMinor: 123_457, currency: 'JPY' } },
    ],
    transfers: [
      {
        fromUserId: '11111111-1111-4111-8111-111111111111',
        toUserId: '22222222-2222-4222-8222-222222222222',
        toAccountType: 'member',
        amount: { amountMinor: 12_345_678, currency: 'INR' },
        status: 'pending',
      },
      {
        fromUserId: '44444444-4444-4444-8444-444444444444',
        toUserId: '22222222-2222-4222-8222-222222222222',
        toAccountType: 'fx_clearing',
        amount: { amountMinor: 123_457, currency: 'JPY' },
        status: 'pending',
      },
    ],
  },
  partitions: [
    {
      currency: 'INR',
      grossTransferCount: 12,
      netTransferCount: 1,
      grossTax: { amountMinor: 600, currency: 'INR' },
      netTax: { amountMinor: 50, currency: 'INR' },
      taxSaved: { amountMinor: 550, currency: 'INR' },
    },
    {
      currency: 'JPY',
      grossTransferCount: 4,
      netTransferCount: 1,
      grossTax: { amountMinor: 200, currency: 'JPY' },
      netTax: { amountMinor: 50, currency: 'JPY' },
      taxSaved: { amountMinor: 150, currency: 'JPY' },
    },
  ],
};

/** The reference data, and the preview, for one group. */
export async function stubApi(
  page: Page,
  options: { readonly preview?: NettingPreview } = {},
): Promise<void> {
  await page.route('**/v1/currencies', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CURRENCIES) }),
  );

  await page.route('**/v1/groups/*/netting/preview*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(options.preview ?? PREVIEW),
    }),
  );
}

/** A group with nothing open in it: the same shape, with no partitions and nothing to settle. */
export const EMPTY_PREVIEW: NettingPreview = {
  currency: 'INR',
  partitions: [],
  bestCase: { positions: [], transfers: [] },
};
