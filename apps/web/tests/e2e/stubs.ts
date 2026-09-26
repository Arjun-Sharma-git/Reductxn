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

// --- Obligations ---------------------------------------------------------------------------------------

export type Member = {
  readonly userId: string;
  readonly displayName: string;
  readonly role: 'admin' | 'member';
};

export const MEMBERS: Member[] = [
  { userId: '11111111-1111-4111-8111-111111111111', displayName: 'Asha Menon', role: 'admin' },
  { userId: '22222222-2222-4222-8222-222222222222', displayName: 'Bhavesh Rao', role: 'member' },
  { userId: '33333333-3333-4333-8333-333333333333', displayName: 'Chitra Iyer', role: 'member' },
];

export type Obligation = {
  id: string;
  groupId: string;
  debtorId: string;
  creditorId: string;
  amount: { amountMinor: number; currency: string };
  settleInCurrency?: string;
  memo?: string;
  status: 'open' | 'netted' | 'settled' | 'voided';
  occurredAt: string;
};

/** One recorded commitment, so the list has something to show before anything is recorded in a test. */
export const OBLIGATION: Obligation = {
  id: '44444444-4444-4444-8444-444444444444',
  groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
  debtorId: MEMBERS[1]!.userId,
  creditorId: MEMBERS[0]!.userId,
  amount: { amountMinor: 125_000, currency: 'INR' },
  memo: 'Dinner at the club',
  status: 'open',
  occurredAt: '2026-09-20T18:30:00Z',
};

/** What the record endpoint should answer with, and how long it takes to say so. */
export type RecordBehaviour =
  | { readonly kind: 'accept' }
  | { readonly kind: 'refuse'; readonly code: string; readonly status: number };

export type PostedRequest = {
  readonly idempotencyKey: string;
  readonly body: Record<string, unknown>;
};

/**
 * The obligations API: members, currencies, the list, the record endpoint and the reference rate.
 *
 * It returns the requests it received, which is how the browser tests assert the two things no screenshot can
 * show: that a refusal decided locally never left the browser, and that a retry carries the *same* idempotency
 * key as the attempt it repeats (P8). A stub that only answered would make both claims untestable.
 */
export async function stubObligationsApi(
  page: Page,
  options: {
    readonly list?: readonly Obligation[];
    readonly behaviour?: RecordBehaviour;
    readonly delayMs?: number;
    readonly rate?: { base: string; quote: string; rateScaled: number; rateScale: number };
  } = {},
): Promise<readonly PostedRequest[]> {
  const posted: PostedRequest[] = [];
  /*
   * The stub remembers what it accepted, because a stub that answers `201` and then returns an unchanged list is a
   * server that loses the write. That is not pedantry: the client invalidates the list after a successful record, so
   * a stateless stub made the refetch *erase* the row the test had just watched arrive - which is a real bug
   * somewhere, and this made sure it was not the client's.
   */
  const recorded: Obligation[] = [];

  await page.route('**/v1/currencies', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CURRENCIES) }),
  );

  await page.route('**/v1/groups/*/members', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MEMBERS) }),
  );

  await page.route('**/v1/fx/rates*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        base: options.rate?.base ?? 'INR',
        quote: options.rate?.quote ?? 'JPY',
        rateScaled: options.rate?.rateScaled ?? 55_904,
        rateScale: options.rate?.rateScale ?? 5,
        quotedAt: '2026-09-26T14:00:00Z',
        expiresAt: '2026-09-27T14:00:00Z',
        source: 'stub',
      }),
    }),
  );

  await page.route('**/v1/obligations*', async (route) => {
    if (route.request().method() !== 'POST') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([...recorded, ...(options.list ?? [OBLIGATION])]),
      });

      return;
    }

    const body = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;

    posted.push({
      idempotencyKey: route.request().headers()['idempotency-key'] ?? '',
      body,
    });

    const behaviour = options.behaviour ?? { kind: 'accept' };

    if (behaviour.kind === 'refuse') {
      await route.fulfill({
        status: behaviour.status,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: `https://reductxn.io/problems/${behaviour.code}`,
          title: behaviour.code,
          status: behaviour.status,
          code: behaviour.code,
          params: {},
        }),
      });

      return;
    }

    if (options.delayMs !== undefined) {
      // A slow server, so a test can see the optimistic row before the answer arrives.
      await new Promise((resolve) => setTimeout(resolve, options.delayMs));
    }

    const accepted: Obligation = {
      id: '55555555-5555-4555-8555-555555555555',
      groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
      debtorId: body['debtorId'] as string,
      creditorId: body['creditorId'] as string,
      amount: body['amount'] as Obligation['amount'],
      ...(body['settleInCurrency'] === undefined
        ? {}
        : { settleInCurrency: body['settleInCurrency'] as string }),
      memo: body['memo'] as string,
      status: 'open',
      occurredAt: body['occurredAt'] as string,
    };

    recorded.unshift(accepted);

    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(accepted),
    });
  });

  return posted;
}
