import { expect, test, type Page } from '@playwright/test';

import { ME, WAITING_RUN, signIn, stubIdentityApi, stubSettlementApi } from './stubs';

/**
 * The approval path, end to end in a browser: find a run, read it, answer it.
 *
 * This is the flow that did not exist until ADR 0030: the endpoints for reading a run and recording an answer have
 * been there for several slices, and there was nowhere a member could reach them. So the assertions are about what a
 * member can now *do* - and about the two claims the contract makes that only a screen can honour: that an answer is
 * recorded rather than decided, and that an answer is never overwritten.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const RUNS = `/groups/${GROUP}/settlement`;
const RUN = `${RUNS}/${WAITING_RUN.id}`;

/**
 * Opens a run as a signed-in member, because the session is who answers since ADR 0053.
 *
 * <p>This helper used to take a member and select them in the form's picker. Both are gone: the answer's author is
 * the caller, the contract's body has no field for one, and signing in is what makes an answer possible at all -
 * which is why every test that answers stubs the identity API and signs in first.
 */
async function open(page: Page): Promise<void> {
  await signIn(page);
  await page.goto(RUN);
}

test('closes the group’s books and sees the cycle waiting to be computed', async ({ page }) => {
  const api = await stubSettlementApi(page);
  await stubIdentityApi(page);

  // Signed in, because closing a group's books is a member's act (ADR 0049) and the stub refuses the write without the
  // session's token. The runs read below is untouched by the rule, so nothing else in this file needs a session.
  await signIn(page);

  await page.goto(RUNS);

  await page.getByLabel('Close the books at').fill('2026-10-31T23:59');
  await page.getByRole('button', { name: 'Open a cycle' }).click();

  // The member's own request, back as a row - which is what the read exists for. Before it, asking for a settlement
  // and being refused by the worker looked identical from here: nothing.
  await expect(page.getByText(/^Cycle opened for/)).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Waiting to be computed' }).first()).toBeVisible();

  // The wire carries an instant, not the wall clock the member typed: the control has no timezone and the ledger
  // stores UTC, so a form that sent the local string would record a settlement hours out.
  expect(api.opened).toHaveLength(1);
  expect(String(api.opened[0]!.asOf)).toMatch(/Z$/);
  expect(new Date(String(api.opened[0]!.asOf)).getTime()).toBe(new Date('2026-10-31T23:59').getTime());
});

test('a cycle with a plan links to it, and one without says it is waiting', async ({ page }) => {
  await stubSettlementApi(page);
  await page.goto(RUNS);

  await expect(page.getByRole('cell', { name: 'Waiting to be computed' })).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open the plan' })).toHaveAttribute(
    'href',
    new RegExp(WAITING_RUN.id),
  );

  await page.getByRole('link', { name: 'Open the plan' }).click();
  await expect(page.getByRole('heading', { name: 'Settlement plan' })).toBeVisible();
});

test('lists the group’s runs with what each one moves, per currency', async ({ page }) => {
  await stubSettlementApi(page);
  await page.goto(RUNS);

  await expect(page.getByRole('cell', { name: 'Waiting for answers' })).toBeVisible();
  // The net movement in its own currency, and no total across currencies: nothing adds rupees to yen.
  await expect(page.getByRole('cell', { name: '₹3,000.00' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '1 of 3 so far' })).toBeVisible();

  await page.getByRole('link', { name: 'Open' }).first().click();
  await expect(page).toHaveURL(new RegExp(WAITING_RUN.id));
  await expect(page.getByRole('heading', { name: 'Settlement plan' })).toBeVisible();
});

test('says a state it does not know rather than showing the identifier', async ({ page }) => {
  // A worker that learned a ninth status, and a read model that reports it: the screen has to have an answer.
  await stubSettlementApi(page, { run: { ...WAITING_RUN, status: 'reconciling' } });
  await page.goto(RUNS);

  await expect(page.getByText('A state this screen does not know')).toBeVisible();
  await expect(page.getByText('reconciling', { exact: true })).toHaveCount(0);
});

test('shows what the plan asks for, in names, and names the group’s own conversion', async ({ page }) => {
  await stubSettlementApi(page);
  await page.goto(RUN);

  // The gates are the worker's conclusion, projected: "one short" is a sentence this screen can say because
  // settlement_run_gates holds the arithmetic rather than because the client counted the answers.
  await expect(page.getByText('1 of 3 approvals so far, so 2 short.')).toBeVisible();
  await expect(page.getByText('2 members who owe money still have to confirm.')).toBeVisible();

  // Who pays whom, by name.
  await expect(page.getByRole('rowheader', { name: 'Asha Menon' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Chitra Iyer' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '₹2,600.00' })).toBeVisible();

  // And the leg that is not a member's payment: the group's currency conversion, labelled as the group's own.
  await expect(page.getByRole('cell', { name: "The group's currency conversion" })).toBeVisible();
});

test('records an answer and shows it on the record without claiming it counted', async ({ page }) => {
  const api = await stubSettlementApi(page);
  await stubIdentityApi(page);
  await open(page);

  await page.getByRole('button', { name: 'Approve this plan' }).click();

  // The server's word for the answer, and its own hedge: whether it counts is the plan's decision, and its status
  // is where that shows.
  await expect(page.getByText("Recorded: Approved. Whether it counts is the plan's own decision")).toBeVisible();

  // **The body names nobody**, which is the client-side half of ADR 0053 and the reason the picker is gone: the
  // session says who is answering, so a client that cannot name a member cannot answer as somebody else. Asserted as
  // the exact body rather than as a field's absence, because that is what a stale client would get wrong.
  expect(api.posted).toHaveLength(1);
  expect(api.posted[0]).toEqual({ decision: 'approve' });

  // And the answer on the record belongs to the signed-in member rather than to anybody the request mentioned.
  expect(api.answers[0]!.memberId).toBe(ME.id);

  // And it is on the record: the run is re-read, so the answer appears in the answer table.
  await expect(page.getByText('Nobody has answered this plan yet.')).toHaveCount(0);
  await expect(page.getByRole('cell', { name: 'Approved' }).first()).toBeVisible();
});

test('a refusal is recorded as a refusal, not as a failure', async ({ page }) => {
  const api = await stubSettlementApi(page);
  await stubIdentityApi(page);
  await open(page);

  await page.getByRole('button', { name: 'Refuse this plan' }).click();

  await expect(page.getByText('Recorded: Refused.')).toBeVisible();
  expect(api.posted[0]).toEqual({ decision: 'reject' });
  expect(api.answers[0]!.memberId).toBe(ME.id);
});

/**
 * A plan nobody can read without a session, which is the read's answer since ADR 0054.
 *
 * <p><strong>This test asserted the opposite one slice ago and the sentence it was written on is the one this slice
 * corrected.</strong> It said: "a member looking at a plan without a session, which the read allows and the answer does
 * not (ADR 0053)". The read allowed it - `GET /runs/{runId}` served a bare run uuid - and that was the hole: a plan's
 * figures, its amounts and its answers were readable by anybody holding an id. The read is a member's now, so a session
 * that has ended is told so *at the read* rather than at the answer, and there is no form to offer.
 */
test('a plan cannot be read without a session, and the read says so', async ({ page }) => {
  await stubSettlementApi(page, { signedIn: false });
  await page.goto(RUN);

  await expect(page.getByText('You are not signed in, or your session has ended.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve this plan' })).toHaveCount(0);
  await expect(page.getByRole('cell', { name: 'Approved' })).toHaveCount(0);
});

test('stops offering the answer when the member has already answered', async ({ page }) => {
  await stubSettlementApi(page, {
    decision: { kind: 'refuse', code: 'api.decision_already_answered', status: 409 },
  });
  await stubIdentityApi(page);
  await open(page);

  await page.getByRole('button', { name: 'Approve this plan' }).click();

  // The contract: an answer is never overwritten. So the screen says what the server said and stops asking - the
  // buttons go rather than staying there inviting something the system will not do.
  await expect(page.getByText('This member has already answered this run.')).toBeVisible();
  await expect(page.getByText('an answer is never overwritten')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve this plan' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refuse this plan' })).toHaveCount(0);
});
