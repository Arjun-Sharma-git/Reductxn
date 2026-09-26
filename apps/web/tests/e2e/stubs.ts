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
  options: { readonly preview?: NettingPreview; readonly balances?: Balances } = {},
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

  await page.route('**/v1/groups/*/balances', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(options.balances ?? BALANCES),
    }),
  );
}

/** A group with nothing open in it: the same shape, with no partitions and nothing to settle. */
export const EMPTY_PREVIEW: NettingPreview = {
  currency: 'INR',
  partitions: [],
  bestCase: { positions: [], transfers: [] },
};

// --- Balances ------------------------------------------------------------------------------------------

export type Balances =
  paths['/groups/{groupId}/balances']['get']['responses']['200']['content']['application/json'];

/**
 * The balances read, in the shape the contract gives it: a flat list of positions, one per member per currency.
 *
 * The third entry is the currency clearing account rather than a person - the case the schema cannot label yet, and
 * the one the screen has to show honestly (ADR 0039).
 */
export const BALANCES: Balances = [
  { userId: '11111111-1111-4111-8111-111111111111', direction: 'pay', net: { amountMinor: 12_345_678, currency: 'INR' } },
  { userId: '22222222-2222-4222-8222-222222222222', direction: 'receive', net: { amountMinor: 12_345_678, currency: 'INR' } },
  { userId: '99999999-9999-4999-8999-999999999999', direction: 'flat', net: { amountMinor: 0, currency: 'INR' } },
  { userId: '44444444-4444-4444-8444-444444444444', direction: 'receive', net: { amountMinor: 123_457, currency: 'JPY' } },
];

/** A group with no open commitments: an empty list rather than an error. */
export const NO_BALANCES: Balances = [];

// --- Tax -----------------------------------------------------------------------------------------------

export type TaxRule = paths['/tax/rules']['get']['responses']['200']['content']['application/json'][number];
export type TaxReportLine =
  paths['/tax/reports']['get']['responses']['200']['content']['application/json'][number];

/**
 * Two rules of different shapes, in two currencies, one bounded and one open: a screen that rendered only the first
 * shape or only the first currency would fail rather than pass.
 */
export const TAX_RULES: TaxRule[] = [
  {
    id: 'aaaa1111-1111-4111-8111-111111111111',
    jurisdiction: 'IN',
    currency: 'INR',
    kind: 'flat',
    params: { perTransferMinor: 50 },
    effectiveFrom: '2020-01-01T00:00:00Z',
    effectiveTo: null,
    sourceRef: 'FY2020 s.7(3)',
  },
  {
    id: 'bbbb2222-2222-4222-8222-222222222222',
    jurisdiction: 'IN',
    currency: 'JPY',
    kind: 'advalorem',
    params: { rateBps: 150, minMinor: 0 },
    effectiveFrom: '2024-04-01T00:00:00Z',
    effectiveTo: '2030-03-31T00:00:00Z',
    sourceRef: 'FY2024 s.9(1)',
  },
];

/** A report over one plan in each currency, so both exponents are exercised. */
export const TAX_REPORT: TaxReportLine[] = [
  {
    currency: 'INR',
    grossTax: { amountMinor: 600, currency: 'INR' },
    netTax: { amountMinor: 50, currency: 'INR' },
    taxSaved: { amountMinor: 550, currency: 'INR' },
    runCount: 1,
  },
  {
    currency: 'JPY',
    grossTax: { amountMinor: 200, currency: 'JPY' },
    netTax: { amountMinor: 50, currency: 'JPY' },
    taxSaved: { amountMinor: 150, currency: 'JPY' },
    runCount: 1,
  },
];

/** No plan computed in the period: an empty report rather than an error. */
export const TAX_REPORT_EMPTY: TaxReportLine[] = [];

export type TaxRecorder = {
  /** Every report request the screen sent, as the query string the server would have read. */
  readonly reportRequests: readonly string[];
};

/**
 * The tax API: the rules in force, and the group's report.
 *
 * The report handler records the query it was asked. The period is the one part of this screen that is a *question*
 * rather than a rendering, and a test that only read the URL back would not know whether the screen asked the server
 * the question the URL describes.
 */
export async function stubTaxApi(
  page: Page,
  options: { readonly rules?: readonly TaxRule[]; readonly report?: readonly TaxReportLine[] } = {},
): Promise<TaxRecorder> {
  const reportRequests: string[] = [];

  await page.route('**/v1/currencies', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CURRENCIES) }),
  );

  await page.route('**/v1/tax/rules', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(options.rules ?? TAX_RULES),
    }),
  );

  await page.route('**/v1/tax/reports*', (route) => {
    reportRequests.push(new URL(route.request().url()).search);

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(options.report ?? TAX_REPORT),
    });
  });

  return { reportRequests };
}

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

// --- Settlement runs -----------------------------------------------------------------------------------

export type RunStub = {
  id: string;
  cycleId: string;
  groupId: string;
  status: string;
  computedAt: string;
  partitions: {
    currency: string;
    gross: { amountMinor: number; currency: string };
    net: { amountMinor: number; currency: string };
    tax: { amountMinor: number; currency: string };
    taxSaved: { amountMinor: number; currency: string };
    transferCount: number;
  }[];
  answers: { memberId: string; decision: string; decidedAt: string }[];
  gates?: {
    concludedAt: string;
    status: string;
    quorum: { met: boolean; counted: number; required: number; debtorsStillToConfirm: number };
    funding: { satisfied: boolean; outstanding: { amountMinor: number; currency: string }[] };
  } | null;
};

export type TransferStub = {
  railRef: string;
  fromUserId: string;
  toUserId: string | null;
  toAccountType: 'member' | 'fx_clearing';
  amount: { amountMinor: number; currency: string };
  status: string;
  failureReason?: string | null;
};

export type CycleStub = paths['/groups/{groupId}/cycles']['get']['responses']['200']['content']['application/json'][number];

const RUN_ID = '77777777-7777-4777-8777-777777777777';
const CYCLE_ID = '88888888-8888-4888-8888-888888888888';

/**
 * Two cycles: the one the waiting run is the plan for, and one nobody has computed.
 *
 * Both states a row can be in, because the screen's whole job is to tell them apart - and the second one is the
 * interesting case, since "waiting to be computed" is what a member sees between asking for a settlement and the
 * worker's cadence reaching it.
 */
export const CYCLES: CycleStub[] = [
  {
    id: CYCLE_ID,
    groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
    asOf: '2026-09-30T23:59:59Z',
    createdAt: '2026-09-30T12:00:00Z',
    runId: RUN_ID,
  },
  {
    id: '99999999-9999-4999-8999-999999999999',
    groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
    asOf: '2026-08-31T23:59:59Z',
    createdAt: '2026-09-26T09:00:00Z',
  },
];

/** A run waiting for answers, with a currency gap as well, so the gates have something to say. */
export const WAITING_RUN: RunStub = {
  id: RUN_ID,
  cycleId: CYCLE_ID,
  groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
  status: 'awaiting_approval',
  computedAt: '2026-09-26T09:15:00Z',
  partitions: [
    {
      currency: 'INR',
      gross: { amountMinor: 500_000, currency: 'INR' },
      net: { amountMinor: 300_000, currency: 'INR' },
      tax: { amountMinor: 400, currency: 'INR' },
      taxSaved: { amountMinor: 1_200, currency: 'INR' },
      transferCount: 2,
    },
  ],
  answers: [],
  gates: {
    concludedAt: '2026-09-26T09:20:00Z',
    status: 'awaiting_approval',
    quorum: { met: false, counted: 1, required: 3, debtorsStillToConfirm: 2 },
    funding: { satisfied: false, outstanding: [{ amountMinor: 12_500, currency: 'INR' }] },
  },
};

export const RUN_TRANSFERS: TransferStub[] = [
  {
    railRef: `run:${RUN_ID}:inr:aa`,
    fromUserId: MEMBERS[1]!.userId,
    toUserId: null,
    toAccountType: 'fx_clearing',
    amount: { amountMinor: 40_000, currency: 'INR' },
    status: 'pending',
  },
  {
    railRef: `run:${RUN_ID}:inr:mm`,
    fromUserId: MEMBERS[0]!.userId,
    toUserId: MEMBERS[2]!.userId,
    toAccountType: 'member',
    amount: { amountMinor: 260_000, currency: 'INR' },
    status: 'pending',
  },
];

/** What the decision endpoint should answer. */
export type DecisionBehaviour =
  | { readonly kind: 'accept' }
  | { readonly kind: 'refuse'; readonly code: string; readonly status: number };

export type SettlementRecorder = {
  readonly posted: readonly Record<string, unknown>[];
  /** The answers the endpoint has recorded, so a test can see that one was stored. */
  readonly answers: readonly { readonly memberId: string; readonly decision: string }[];
  /** The cycle requests, so a test can see the instant that was sent rather than the one that was typed. */
  readonly opened: readonly Record<string, unknown>[];
};

/**
 * The settlement API: the group's runs, one run, its transfers and the decision endpoint.
 *
 * One handler for `/v1/runs/*` that dispatches on the path, rather than several overlapping patterns: Playwright
 * matches routes in reverse registration order, so a "specific one before the generic one" arrangement holds only
 * until somebody registers a route in the wrong place - and a test that fails because of that is a test about the
 * stub instead of about the product.
 *
 * The recorder is what lets a test look at the requests and at what the endpoint now holds: the answer that was sent,
 * and (for the refusal case) that nothing was recorded.
 */
export async function stubSettlementApi(
  page: Page,
  options: {
    readonly runs?: readonly RunStub[];
    readonly run?: RunStub;
    readonly transfers?: readonly TransferStub[];
    readonly decision?: DecisionBehaviour;
    readonly cycles?: readonly CycleStub[];
  } = {},
): Promise<SettlementRecorder> {
  const posted: Record<string, unknown>[] = [];
  const answers: { memberId: string; decision: string }[] = [];
  const opened: Record<string, unknown>[] = [];
  const base = options.run ?? WAITING_RUN;
  const cycles: CycleStub[] = [...(options.cycles ?? CYCLES)];

  await page.route('**/v1/currencies', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CURRENCIES) }),
  );

  await page.route('**/v1/groups/*/members', (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MEMBERS) }),
  );

  await page.route('**/v1/groups/*/runs', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([{ ...base, answers: withDates(answers) }, ...(options.runs ?? [])]),
    }),
  );

  // The cycles: read the list, and open one. A POST answers with the row it wrote and appends it to what the list
  // serves, so a test can watch the member's own request come back as a row - which is the whole point of the read.
  await page.route('**/v1/groups/*/cycles', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(cycles) });

      return;
    }

    const body = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;

    opened.push(body);

    const written: CycleStub = {
      id: `0000000${opened.length}-0000-4000-8000-000000000000`,
      groupId: base.groupId,
      asOf: String(body['asOf']),
      createdAt: '2026-09-26T10:00:00Z',
    };

    cycles.unshift(written);

    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(written),
    });
  });

  await page.route('**/v1/runs/**', async (route) => {
    const path = new URL(route.request().url()).pathname;

    if (path.endsWith('/transfers')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(options.transfers ?? RUN_TRANSFERS),
      });

      return;
    }

    if (path.endsWith('/decisions')) {
      const body = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;

      posted.push(body);

      const behaviour = options.decision ?? { kind: 'accept' };

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

      const decision = typeof body['decision'] === 'string' ? body['decision'] : 'approve';

      answers.push({ memberId: String(body['memberId']), decision });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          runId: base.id,
          memberId: body['memberId'],
          decision,
          recordedAt: '2026-09-26T10:00:00Z',
        }),
      });

      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...base, answers: withDates(answers) }),
    });
  });

  return { posted, answers, opened };
}

export type MembersRecorder = {
  /** The memberships the endpoint recorded, so a test can see what was sent rather than what the screen shows. */
  readonly recorded: readonly Record<string, unknown>[];
};

/**
 * The members API: the group's members, and recording one.
 *
 * A POST answers with the row it wrote and appends it to what the list serves, so a test watches the member's own
 * request come back as a row - the read and the write are one flow rather than two endpoints, which is the whole
 * reason the write exists (ADR 0035). The existing `MEMBERS` fixture is reused rather than copied, so the names the
 * obligations and settlement screens fall back on are the same names this screen shows.
 */
export async function stubMembersApi(
  page: Page,
  members: readonly Member[] = MEMBERS,
): Promise<MembersRecorder> {
  const recorded: Record<string, unknown>[] = [];
  const current: Member[] = [...members];

  await page.route('**/v1/groups/*/members', async (route) => {
    if (route.request().method() === 'GET') {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(current) });

      return;
    }

    const body = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;

    recorded.push(body);

    const written = {
      userId: String(body['userId']),
      displayName: String(body['displayName']),
      role: body['role'] === 'admin' ? ('admin' as const) : ('member' as const),
    };

    current.push(written);

    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(written) });
  });

  return { recorded };
}

/** Answers as the read model serves them, so the screen's answer table has a row per answer. */
function withDates(
  answers: readonly { readonly memberId: string; readonly decision: string }[],
): { memberId: string; decision: string; decidedAt: string }[] {
  return answers.map((answer) => ({ ...answer, decidedAt: '2026-09-26T10:00:00Z' }));
}
