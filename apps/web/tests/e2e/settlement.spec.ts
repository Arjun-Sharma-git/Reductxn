import { expect, test, type Page } from '@playwright/test';

import { MEMBERS, WAITING_RUN, stubSettlementApi } from './stubs';

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
const ASHA = MEMBERS[0]!.userId;
const BHAVESH = MEMBERS[1]!.userId;

/** Opens a run and picks the member who is answering. */
async function open(page: Page, member = ASHA): Promise<void> {
  await page.goto(RUN);
  await page.getByLabel('Answering as').selectOption(member);
}

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
  await open(page);

  await page.getByRole('button', { name: 'Approve this plan' }).click();

  // The server's word for the answer, and its own hedge: whether it counts is the plan's decision, and its status
  // is where that shows.
  await expect(page.getByText("Recorded: Approved. Whether it counts is the plan's own decision")).toBeVisible();
  expect(api.posted).toHaveLength(1);
  expect(api.posted[0]).toEqual({ memberId: ASHA, decision: 'approve' });

  // And it is on the record: the run is re-read, so the answer appears in the answer table.
  await expect(page.getByText('Nobody has answered this plan yet.')).toHaveCount(0);
  await expect(page.getByRole('cell', { name: 'Approved' }).first()).toBeVisible();
});

test('a refusal is recorded as a refusal, not as a failure', async ({ page }) => {
  const api = await stubSettlementApi(page);
  await open(page, BHAVESH);

  await page.getByRole('button', { name: 'Refuse this plan' }).click();

  await expect(page.getByText('Recorded: Refused.')).toBeVisible();
  expect(api.posted[0]).toEqual({ memberId: BHAVESH, decision: 'reject' });
});

test('stops offering the answer when the member has already answered', async ({ page }) => {
  await stubSettlementApi(page, {
    decision: { kind: 'refuse', code: 'api.decision_already_answered', status: 409 },
  });
  await open(page);

  await page.getByRole('button', { name: 'Approve this plan' }).click();

  // The contract: an answer is never overwritten. So the screen says what the server said and stops asking - the
  // buttons go rather than staying there inviting something the system will not do.
  await expect(page.getByText('This member has already answered this run.')).toBeVisible();
  await expect(page.getByText('an answer is never overwritten')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve this plan' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Refuse this plan' })).toHaveCount(0);
});
