import { expect, type Page, type Route } from '@playwright/test';

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
    isSignedIn(route)
      ? route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(options.preview ?? PREVIEW),
        })
      : route.fulfill(NOT_SIGNED_IN),
  );

  await page.route('**/v1/groups/*/balances', (route) =>
    isSignedIn(route)
      ? route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(options.balances ?? BALANCES),
        })
      : route.fulfill(NOT_SIGNED_IN),
  );

  await signedInReader(page);
}

/**
 * The session a stub insists on, and the refusal it answers with when there is not one.
 *
 * **Every group read requires a member since ADR 0054**, so the stubs that serve them answer `401` without the token the
 * identity stub issued - exactly as the API does. That is what makes "the client sends the token" an assertion rather
 * than a claim: a screen that rendered a group's plan while sending no credential would fail here rather than pass.
 */
function isSignedIn(route: Route): boolean {
  return route.request().headers()['authorization'] === `Bearer ${TOKEN_PAIR.accessToken}`;
}

const NOT_SIGNED_IN = {
  status: 401,
  contentType: 'application/problem+json',
  body: JSON.stringify({
    type: 'about:blank',
    title: 'Unauthorized',
    status: 401,
    detail: 'stubbed refusal',
    code: 'api.not_signed_in',
  }),
} as const;

/**
 * A signed-in reader, for the stub helpers that serve a group's own data.
 *
 * **The read helpers call this rather than each spec**, and the reason is what the reads became: nine of them now answer
 * `401` without a member's session, so a helper that served them without a session would be arming every spec with a
 * page that cannot read anything. The sign-in goes through the two steps a person does - the number, then the code -
 * which is why these helpers also register the identity stub.
 *
 * A spec that is *about* the signed-out answer registers its own stubs and does not call a read helper, or it passes
 * `{ signedIn: false }` where the helper offers it.
 */
async function signedInReader(page: Page): Promise<void> {
  await stubIdentityApi(page);
  await signIn(page);
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
    // A group's tax is its own since ADR 0054, so the stub refuses without the session's token. The *rules* read above
    // is deliberately unguarded: which rules are in force is the same answer for everybody.
    if (!isSignedIn(route)) {
      return route.fulfill(NOT_SIGNED_IN);
    }

    reportRequests.push(new URL(route.request().url()).search);

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(options.report ?? TAX_REPORT),
    });
  });

  await signedInReader(page);

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
    /**
     * How the void answers. The refusal is a parameter rather than a route of its own, so the test about a plan
     * holding a commitment asks for the same request and gets the answer it is about.
     */
    readonly voidBehaviour?: RecordBehaviour;
    /**
     * A caller-owned list this stub appends the voided ids to.
     *
     * The alternative was to widen this helper's return type, which fifteen tests destructure as a plain array of
     * recorded requests. A recorder passed in is smaller than a breaking change to a stub nobody else's test needs
     * and keeps the assertion - "the client asked to void *this* row and no other" - available where it matters.
     */
    readonly voids?: string[];
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

  /* Rows this stub has voided. A stub that answered the void and then served the old list would let the client's
     refetch erase the change it had just made - the failure this file's own comment records for the record path. */
  const voided = new Set<string>();

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
      // The list is a group's ledger, and since ADR 0054 it requires a member of the group - the same rule the write
      // below has asked since ADR 0049.
      if (!isSignedIn(route)) {
        await route.fulfill(NOT_SIGNED_IN);

        return;
      }

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          [...recorded, ...(options.list ?? [OBLIGATION])].map((obligation) =>
            voided.has(obligation.id) ? { ...obligation, status: 'voided' as const } : obligation,
          ),
        ),
      });

      return;
    }

    // A member of the group is who may record into it (ADR 0049), so the stub refuses a write without the session's
    // token exactly as the API does - the client sends the token on every request, and this is where that is proved.
    if (route.request().headers()['authorization'] !== `Bearer ${TOKEN_PAIR.accessToken}`) {
      return route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'stubbed refusal',
          code: 'api.not_signed_in',
        }),
      });
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

  /*
   * The void, registered after the list route on purpose: Playwright runs the handlers that match in the reverse
   * order they were registered, so the last one added wins - and `**\/v1/obligations*` would otherwise answer this
   * path as a list read, which is a `200` with an array where the operation promises the obligation.
   */
  await page.route('**/v1/obligations/*/void', async (route) => {
    const id = new URL(route.request().url()).pathname.split('/').slice(-2)[0] ?? '';

    options.voids?.push(id);

    const behaviour = options.voidBehaviour ?? { kind: 'accept' };

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

    const row = [...recorded, ...(options.list ?? [OBLIGATION])].find((obligation) => obligation.id === id);

    if (row === undefined) {
      // The server's answer for an id nobody has: the same `404` a stranger's group gets (ADR 0045), so a test that
      // voids a row the stub does not serve sees the real refusal rather than a `200` invented here.
      await route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'https://reductxn.io/problems/api.no_such_obligation',
          title: 'api.no_such_obligation',
          status: 404,
          code: 'api.no_such_obligation',
          params: {},
        }),
      });

      return;
    }

    voided.add(id);

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ ...row, status: 'voided' as const }),
    });
  });

  await signedInReader(page);

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
    readonly signedIn?: boolean;
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
    route.fulfill(isSignedIn(route)
      ? { status: 200, contentType: 'application/json', body: JSON.stringify(MEMBERS) }
      : NOT_SIGNED_IN),
  );

  await page.route('**/v1/groups/*/runs', (route) =>
    route.fulfill(isSignedIn(route)
      ? {
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([{ ...base, answers: withDates(answers) }, ...(options.runs ?? [])]),
        }
      : NOT_SIGNED_IN),
  );

  // The cycles: read the list, and open one. A POST answers with the row it wrote and appends it to what the list
  // serves, so a test can watch the member's own request come back as a row - which is the whole point of the read.
  await page.route('**/v1/groups/*/cycles', async (route) => {
    if (route.request().method() === 'GET') {
      // The read is a group's own since ADR 0054, and this comment used to say the opposite: "the read is untouched,
      // which is the half of the gap this slice deliberately left alone". The gap is closed, so both halves of this route
      // now answer 401 without the session's token.
      if (!isSignedIn(route)) {
        await route.fulfill(NOT_SIGNED_IN);

        return;
      }

      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(cycles) });

      return;
    }

    // Closing a group's books is a member's act (ADR 0049): the stub refuses a write without the session's token, as
    // the API does.
    if (!isSignedIn(route)) {
      return route.fulfill(NOT_SIGNED_IN);
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
      // The instruction list is the most sensitive part of the plan, and since ADR 0054 it needs a member's session.
      await route.fulfill(isSignedIn(route)
        ? {
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(options.transfers ?? RUN_TRANSFERS),
          }
        : NOT_SIGNED_IN);

      return;
    }

    if (path.endsWith('/decisions')) {
      // The answering member is the caller since ADR 0053, so the stub refuses a write without the session's token
      // exactly as the API does. Two things a spec proves by getting past here: the client sends its token, and the
      // body it sends names nobody - which is what the assertion on `posted` is for.
      if (!isSignedIn(route)) {
        await route.fulfill(NOT_SIGNED_IN);

        return;
      }

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

      // The caller, because that is who the answer belongs to: the stub does not model membership - that is the API's
      // rule, and its tests are against a real database - but it does model the one member an answer can come from.
      answers.push({ memberId: ME.id, decision });

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          runId: base.id,
          memberId: ME.id,
          decision,
          recordedAt: '2026-09-26T10:00:00Z',
        }),
      });

      return;
    }

    await route.fulfill(isSignedIn(route)
      ? {
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ ...base, answers: withDates(answers) }),
        }
      : NOT_SIGNED_IN);
  });

  if (options.signedIn !== false) {
    await signedInReader(page);
  }

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
  options: { readonly signedIn?: boolean } = {},
): Promise<MembersRecorder> {
  const recorded: Record<string, unknown>[] = [];
  const current: Member[] = [...members];

  await page.route('**/v1/groups/*/members', async (route) => {
    if (route.request().method() === 'GET') {
      // The read names people, and since ADR 0054 it requires a member of the group - the same rule the write below has
      // asked since ADR 0048.
      await route.fulfill(isSignedIn(route)
        ? { status: 200, contentType: 'application/json', body: JSON.stringify(current) }
        : NOT_SIGNED_IN);

      return;
    }

    // Recording somebody needs a session: a member of the group is who may add one (ADR 0048), and the stub refuses
    // without the token exactly as the API does, so a spec that shows a member added has proved the token was sent.
    // The group's membership itself is not modelled here - that is the API's rule, and the tests that pin it are in
    // `AddMemberApiTest`, against a real database.
    if (!isSignedIn(route)) {
      return route.fulfill(NOT_SIGNED_IN);
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

  // A spec that is about the signed-out answer says so: the screen must render the refusal it gets from the read, and a
  // signed-in page cannot produce that.
  if (options.signedIn !== false) {
    await signedInReader(page);
  }

  return { recorded };
}

/** Answers as the read model serves them, so the screen's answer table has a row per answer. */
function withDates(
  answers: readonly { readonly memberId: string; readonly decision: string }[],
): { memberId: string; decision: string; decidedAt: string }[] {
  return answers.map((answer) => ({ ...answer, decidedAt: '2026-09-26T10:00:00Z' }));
}

// --- Invites -------------------------------------------------------------------------------------------

export type Invite = paths['/groups/{groupId}/invites']['post']['responses']['201']['content']['application/json'];

/** A code with a week to live and no limit, which is what this deployment's creation operation produces. */
export const INVITE: Invite = {
  code: 'JKM2345P',
  expiresAt: '2026-10-03T09:00:00Z',
};

export type InviteRecorder = {
  /** Every acceptance the screen sent, as the body the server would have read. */
  readonly acceptances: readonly string[];
};

/**
 * The group a code in this suite joins.
 *
 * It is the id the preview names and the id the acceptance screen links to after joining, and it is the same group the
 * other specs open by path (`3f7c1c1e-...`), so "the link goes to the group you joined" is a claim a test can follow.
 */
export const GROUP_WITH_A_CODE = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';

/**
 * The invite API: making a code, and spending one.
 *
 * The acceptance handler records the body it was asked, because the body *is* the recorded identity gap in action - the
 * page mints the id and sends the name - and a test that only saw "You are in" would not know what the server was told
 * about who joined. A refusal is a parameter rather than a route of its own, so a test can ask for the same request and
 * get the answer it is about.
 */
export async function stubInvitesApi(
  page: Page,
  options: {
    readonly refusal?: { readonly status: number; readonly code: string };
    /**
     * The group a code joins, as the preview answers it. Absent means the default: a group with a name, so the
     * acceptance screen has something to show before anybody types anything. `name: null` is the other real state -
     * a group that exists only as rows, whose preview has no name to show at all (ADR 0043).
     */
    readonly group?: { readonly id: string; readonly name?: string | null; readonly memberCount?: number };
  } = {},
): Promise<InviteRecorder> {
  const acceptances: string[] = [];

  // What a code is for, which is the read ADR 0054 added. It is registered first so the accept route below (which
  // matches a more specific path) is unaffected, and it **does not check for a token**: the contract gives this
  // operation `security: []`, because the person who asks it is the person who has not signed in yet. A stub that
  // demanded a session here would be hiding the reason the operation exists.
  await page.route('**/v1/invites/*', (route) => {
    if (options.refusal !== undefined) {
      return route.fulfill({
        status: options.refusal.status,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: options.refusal.status === 410 ? 'Gone' : 'Not Found',
          status: options.refusal.status,
          detail: 'stubbed refusal',
          code: options.refusal.code,
        }),
      });
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: INVITE.code,
        expiresAt: INVITE.expiresAt,
        group: {
          id: options.group?.id ?? GROUP_WITH_A_CODE,
          // `null` rather than `undefined` for a group with no name: JSON.stringify drops an undefined value, so the two
          // cases would be one, and "this group has a name and it is Flat 3B" would be unassertable.
          ...(options.group?.name === null ? {} : { name: options.group?.name ?? 'Flat 3B' }),
          memberCount: options.group?.memberCount ?? 4,
        },
      }),
    });
  });

  await page.route('**/v1/groups/*/invites', (route) => {
    // A member of the group is who may create a code (ADR 0048): the stub refuses without the session's token, exactly
    // as the creation path does, so the spec that makes a code has proved who it was made by.
    if (route.request().headers()['authorization'] !== `Bearer ${TOKEN_PAIR.accessToken}`) {
      return route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'stubbed refusal',
          code: 'api.not_signed_in',
        }),
      });
    }

    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(INVITE) });
  });

  await page.route('**/v1/invites/*/accept', (route) => {
    acceptances.push(route.request().postData() ?? '');

    // The joiner is the caller now, so this stub refuses a request without the session's token exactly as `/v1/me` does -
    // which is what makes "the client sends the token" an assertion rather than a claim (ADR 0047).
    if (route.request().headers()['authorization'] !== `Bearer ${TOKEN_PAIR.accessToken}`) {
      return route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'stubbed refusal',
          code: 'api.not_signed_in',
        }),
      });
    }

    if (options.refusal !== undefined) {
      return route.fulfill({
        status: options.refusal.status,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: options.refusal.status === 410 ? 'Gone' : 'Not Found',
          status: options.refusal.status,
          detail: 'stubbed refusal',
          code: options.refusal.code,
        }),
      });
    }

    const body = JSON.parse(route.request().postData() ?? '{}') as { displayName: string };

    // The member is the signed-in person, because the body no longer says who is joining: the stub answers the way the
    // server does rather than echoing what it was sent.
    return route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({ userId: ME.id, displayName: body.displayName, role: 'member' }),
    });
  });

  return { acceptances };
}

// --- Identity ------------------------------------------------------------------------------------------

export type TokenPair = paths['/auth/otp/verify']['post']['responses']['200']['content']['application/json'];

/** A session, as the API issues one: two opaque tokens and half an hour. */
export const TOKEN_PAIR: TokenPair = {
  accessToken: 'access-token-from-the-stub',
  refreshToken: 'refresh-token-from-the-stub',
  expiresIn: 1800,
};

/** The signed-in person, as `GET /me` serves them: the deployment's name for them is their number. */
export const ME = { id: '7a1e5b30-2f5c-4a1e-9c3d-2b6a4f8e1d90', displayName: '+919812345678', kycTier: 'none' };

/**
 * Sign in the way a person does, so a session the screens need really exists.
 *
 * It is not a shortcut: the identity stub, the groups stub and the invite acceptance all answer `401` to a request
 * without the token, so a test that skipped this would be testing a screen nobody can reach. It lives here rather than in
 * the spec that first needed it because four specs need a caller now (ADR 0047), and going through the two steps means
 * each of them exercises the flow the sign-in slice built - which is the honest price of a screen that needs a caller.
 */
export async function signIn(page: Page) {
  await page.goto('/sign-in');
  await page.getByLabel('Phone number').fill('+919812345678');
  await page.getByRole('button', { name: 'Send me a code' }).click();
  await page.getByLabel('Code').fill('481516');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByText(/Signed in as/)).toBeVisible();
}

export type IdentityRecorder = {
  /** Every code the screen handed back, so a test can see what the server would have been asked. */
  readonly verified: readonly string[];
  /** How many times a code was asked for. */
  requested(): number;
};

/**
 * The identity API: asking for a code, handing it back, and reading who is signed in.
 *
 * The access token the stub issues is a fixed string rather than a random one, because what the tests assert is that the
 * client *sends* what it was given - `GET /me` with that exact bearer header - and a random token would make that
 * assertion impossible to write. That is also why `/v1/me` refuses a request without the right header: a stub that
 * answered everybody would make "the client sends the token" untestable.
 *
 * `refusal` is how a test asks for the answer that matters: a code this deployment will not accept. The status and the
 * code are separate because identity has three refusals across two statuses (401 for a code or a session, 429 for asking
 * too often), and a test that conflated them would pass for the wrong one.
 */
export async function stubIdentityApi(
  page: Page,
  options: { readonly refusal?: { readonly status: number; readonly code: string } } = {},
): Promise<IdentityRecorder> {
  const verified: string[] = [];
  let requested = 0;

  await page.route('**/v1/auth/otp/request', (route) => {
    requested += 1;

    return route.fulfill({ status: 202, body: '' });
  });

  await page.route('**/v1/auth/otp/verify', (route) => {
    verified.push(route.request().postData() ?? '');

    if (options.refusal !== undefined) {
      return route.fulfill({
        status: options.refusal.status,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: options.refusal.status,
          detail: 'stubbed refusal',
          code: options.refusal.code,
        }),
      });
    }

    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(TOKEN_PAIR) });
  });

  await page.route('**/v1/me', (route) => {
    const authorization = route.request().headers()['authorization'];

    if (authorization !== `Bearer ${TOKEN_PAIR.accessToken}`) {
      return route.fulfill({
        status: 401,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Unauthorized',
          status: 401,
          detail: 'stubbed refusal',
          code: 'api.not_signed_in',
        }),
      });
    }

    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ME) });
  });

  return { verified, requested: () => requested };
}

// --- Groups --------------------------------------------------------------------------------------------

export type GroupSummary = {
  readonly id: string;
  readonly name: string;
  readonly currency: string;
  readonly reportingCurrency: string;
  readonly memberCount: number;
  readonly maxMembers: number;
  readonly createdAt: string;
};

export type GroupsRecorder = {
  /** The bodies the screen posted, so a test can see what was sent and which key it carried. */
  readonly created: readonly { readonly body: unknown; readonly key: string | undefined }[];
};

/** A group as the API serves one. */
export function group(id: string, name: string, currency = 'INR', memberCount = 1): GroupSummary {
  return {
    id,
    name,
    currency,
    reportingCurrency: currency,
    memberCount,
    maxMembers: 200,
    createdAt: '2026-09-01T00:00:00Z',
  };
}

/**
 * The group API: the list, creating one, and the currency catalogue the create form chooses from.
 *
 * The catalogue is stubbed here rather than reused from another spec's helper because this screen needs exactly one thing
 * from it - the codes a `<select>` offers - and a stub that answered everything another screen needs would be a stub whose
 * failures point at the wrong test.
 *
 * `/v1/groups/:new` answers with the group the *request* named, which is what makes the create test able to assert that
 * what appears in the list is what was sent rather than what the stub felt like saying.
 */
export async function stubGroupsApi(
  page: Page,
  groups: readonly GroupSummary[] = [],
  options: { readonly signedIn?: boolean } = {},
): Promise<GroupsRecorder> {
  const created: { body: unknown; key: string | undefined }[] = [];
  const current = [...groups];

  await page.route('**/v1/currencies', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify([
        { code: 'INR', minorUnitExponent: 2 },
        { code: 'USD', minorUnitExponent: 2 },
      ]),
    }),
  );

  // A single group by id, which the group's layout asks for to put a name above the nav.
  await page.route('**/v1/groups/*', (route) => {
    const id = route.request().url().split('/').pop() ?? '';
    const found = current.find((group) => group.id === id);

    return route.fulfill(
      found === undefined
        ? {
            status: 404,
            contentType: 'application/problem+json',
            body: JSON.stringify({
              type: 'about:blank',
              title: 'Not Found',
              status: 404,
              detail: 'stubbed refusal',
              code: 'api.no_such_group',
            }),
          }
        : { status: 200, contentType: 'application/json', body: JSON.stringify(found) },
    );
  });

  await page.route('**/v1/groups', (route) => {
    if (route.request().method() === 'GET') {
      // The session is required, and the stub enforces it. A stub that listed groups for anybody would make "the screen
      // sends the token" untestable - and the signed-out case, which is the ordinary first visit, unrepresentable.
      if (!isSignedIn(route)) {
        return route.fulfill(NOT_SIGNED_IN);
      }

      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(current) });
    }

    const body = (route.request().postDataJSON() ?? {}) as { name?: string; currency?: string };
    const key = route.request().headers()['idempotency-key'];

    created.push({ body, key });

    const made = group(crypto.randomUUID(), String(body.name), String(body.currency));

    current.push(made);

    return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(made) });
  });

  if (options.signedIn !== false) {
    await signedInReader(page);
  }

  return { created };
}

// --- Activity ------------------------------------------------------------------------------------------

export type ActivityRow = {
  readonly kind: 'member_joined' | 'decision_recorded' | 'invitation_created';
  readonly at: string;
  readonly memberId?: string;
  readonly displayName?: string;
  readonly role?: string;
  readonly decision?: string;
  readonly uses?: number;
  readonly maxUses?: number;
};

/**
 * The group's history.
 *
 * The stub answers `404` with the group read's own code when the caller is not a member, because that is what the server
 * does and what the screen has to render: one code for "no such group" and "not a group you are in" (ADR 0043, 0044). A stub
 * that answered `200 []` would make the refusal path untestable and would hide the reason the screen shows a message.
 */
export async function stubActivityApi(
  page: Page,
  events: readonly ActivityRow[],
  options: { readonly forbidden?: boolean; readonly signedIn?: boolean } = {},
): Promise<void> {
  await page.route('**/v1/groups/*/activity**', (route) => {
    // A feed names people, so this read has required a member since ADR 0044 - it was the one read that asked before the
    // rest did. **The stub did not enforce it until ADR 0054**, which is the sort of gap this slice is about: the server
    // refused a stranger and the browser suite could not tell, because the stub answered everybody.
    if (!isSignedIn(route)) {
      return route.fulfill(NOT_SIGNED_IN);
    }

    if (options.forbidden === true) {
      return route.fulfill({
        status: 404,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: 'stubbed refusal',
          code: 'api.no_such_group',
        }),
      });
    }

    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(events) });
  });

  if (options.signedIn !== false) {
    await signedInReader(page);
  }
}
