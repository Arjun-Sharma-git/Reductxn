import { expect, test, type Page } from '@playwright/test';

import { MEMBERS, signIn, stubIdentityApi, stubObligationsApi } from './stubs';

/**
 * Recording a commitment, in a browser, with the API stubbed so that the *requests* can be inspected.
 *
 * The two claims that need a browser are the ones about what does and does not leave the page:
 *
 *   - **A refusal decided locally never becomes a request.** Over-precise input is refused in the form (RULE F12),
 *     and the count of POSTs is zero.
 *   - **A retry repeats the same attempt.** After a `503`, the button offers a retry and the second request carries
 *     the same `Idempotency-Key` as the first (P8) - which is what makes retrying a money write safe rather than a
 *     way to record something twice. A `422` sends nothing the second time, because the answer would be the same.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const URL = `/groups/${GROUP}/obligations`;
const ASHA = MEMBERS[0]!.userId;
const BHAVESH = MEMBERS[1]!.userId;

/** Fills the form with a valid commitment, leaving the amount and currency to the caller. */
async function fill(page: Page, amount: string, currency = 'INR'): Promise<void> {
  await page.getByLabel('Who owes').selectOption(BHAVESH);
  await page.getByLabel('Who is owed').selectOption(ASHA);
  await page.getByLabel('How much').fill(amount);
  await page.getByLabel('Currency').selectOption(currency);
  await page.getByLabel('Reason').fill('Taxi to the airport');
}

test('lists the group’s commitments with names rather than identifiers', async ({ page }) => {
  await stubObligationsApi(page);
  await page.goto(URL);

  await expect(page.getByRole('cell', { name: '1,250.00' }).first()).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Bhavesh Rao' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Dinner at the club' })).toBeVisible();
});

test('shows the new commitment before the server has answered, then the server’s row', async ({ page }) => {
  // A deliberately slow endpoint, so the optimistic row is observable rather than a race the test usually wins.
  await stubObligationsApi(page, { delayMs: 1500, list: [] });
  await stubIdentityApi(page);

  // Signed in, because a member of the group is who may record into it (ADR 0049) - and the stub refuses the write
  // without the session's token, so this test also says the client sent one.
  await signIn(page);

  await page.goto(URL);

  await fill(page, '1250.00');
  await page.getByRole('button', { name: 'Record it' }).click();

  await expect(page.getByRole('cell', { name: 'Recording…' }).first()).toBeVisible();

  // Once the answer arrives the row is the server's, so the pending marker is gone.
  await expect(page.getByRole('cell', { name: 'Recording…' })).toHaveCount(0, { timeout: 8000 });
  await expect(page.getByRole('cell', { name: 'Open' }).first()).toBeVisible();
});

test('refuses an over-precise amount without sending anything', async ({ page }) => {
  const posted = await stubObligationsApi(page, { list: [] });
  await page.goto(URL);

  // Three decimals on a currency with two: refused, not truncated - silently dropping the third digit would lose
  // money, which is the failure P1 exists to prevent.
  await fill(page, '1.239');
  await page.getByRole('button', { name: 'Record it' }).click();

  await expect(page.getByText(/more decimal places/)).toBeVisible();
  expect(posted).toHaveLength(0);
});

test('refuses the same member on both sides without sending anything', async ({ page }) => {
  const posted = await stubObligationsApi(page, { list: [] });
  await page.goto(URL);

  await page.getByLabel('Who owes').selectOption(ASHA);
  await page.getByLabel('Who is owed').selectOption(ASHA);
  await page.getByLabel('How much').fill('100.00');
  await page.getByLabel('Currency').selectOption('INR');
  await page.getByLabel('Reason').fill('To myself');
  await page.getByRole('button', { name: 'Record it' }).click();

  await expect(page.getByText(/same member cannot be both/)).toBeVisible();
  expect(posted).toHaveLength(0);
});

test('offers a retry after a 503, and the retry carries the same idempotency key', async ({ page }) => {
  const posted = await stubObligationsApi(page, {
    behaviour: { kind: 'refuse', code: 'fx.rate_unavailable', status: 503 },
  });
  await stubIdentityApi(page);
  await signIn(page);

  await page.goto(URL);

  await fill(page, '1250.00');
  await page.getByRole('button', { name: 'Record it' }).click();

  // The contract says a 503 means "not yet", so the interface offers the same attempt again rather than pretending
  // it was answered. (The client also retries once by itself, so the first click may already be two requests.)
  const again = page.getByRole('button', { name: 'Try again' });
  await expect(again).toBeVisible();
  await expect(page.getByText(/no exchange rate for this pair yet/)).toBeVisible();

  await again.click();
  await expect(page.getByText(/no exchange rate for this pair yet/)).toBeVisible();

  // The claim P8 rests on: every one of those requests is the *same* attempt, not a second commitment.
  expect(posted.length).toBeGreaterThanOrEqual(2);
  const keys = new Set(posted.map((request) => request.idempotencyKey));
  expect(keys.size).toBe(1);
  expect([...keys][0]).not.toBe('');
});

test('does not offer a retry after a 422, because the answer would be the same', async ({ page }) => {
  const posted = await stubObligationsApi(page, {
    behaviour: { kind: 'refuse', code: 'ledger.currency_mismatch', status: 422 },
  });
  await stubIdentityApi(page);
  // Signed in for the same reason the recording test is: the write requires a member (ADR 0049).
  await signIn(page);

  await page.goto(URL);

  await fill(page, '1250.00', 'INR');
  await page.getByRole('button', { name: 'Record it' }).click();

  await expect(page.getByText(/settles in/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Record it' })).toBeVisible();
  expect(posted).toHaveLength(1);
});

test('states the cost of settling in another currency before it is chosen', async ({ page }) => {
  await stubObligationsApi(page, { list: [] });
  await page.goto(URL);

  await fill(page, '1250.00', 'INR');
  await page.getByLabel('Settle in').selectOption('JPY');

  // Indicative, and the message says so: the rate that counts is the one the server locks at recording time.
  await expect(page.getByText(/Indicative rate: 1 INR is 0.55904 JPY/)).toBeVisible();
  await expect(page.getByText(/fixed when you record this/)).toBeVisible();
});

test('voids a commitment after a second press, and keeps it on the record', async ({ page }) => {
  const voids: string[] = [];
  await stubObligationsApi(page, { voids });
  await page.goto(URL);

  const row = page.getByRole('row', { name: /Bhavesh Rao/ });

  // One press asks, and asks without sending anything: a void cannot be undone, so the question comes first.
  await row.getByRole('button', { name: 'Void' }).click();
  await expect(row.getByText(/it cannot be undone/)).toBeVisible();
  expect(voids).toHaveLength(0);

  await row.getByRole('button', { name: 'Void it' }).click();

  // The row stays and says Voided: a transition rather than a delete (ADR 0004), and the refetch that follows the
  // write says the same thing - a stub that served the old list would make this assertion catch the client's own
  // invalidation erasing the change.
  await expect(row.getByRole('cell', { name: 'Voided' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Bhavesh Rao' })).toBeVisible();

  // The id in the path is the row that was clicked, and only one request left the page. The header assertion is
  // absent on purpose: the contract declares no Idempotency-Key for this operation, so the request carries none.
  expect(voids).toHaveLength(1);
  expect(voids[0]).toMatch(/^[0-9a-f-]{36}$/);

  // And the offer is gone, because the server would now answer 200 with the same row - nothing left to void.
  await expect(row.getByRole('button', { name: 'Void' })).toHaveCount(0);
});

test('puts the row back when a settlement plan holds the commitment', async ({ page }) => {
  const voids: string[] = [];
  await stubObligationsApi(page, {
    voids,
    voidBehaviour: { kind: 'refuse', code: 'api.obligation_not_open', status: 409 },
  });
  await page.goto(URL);

  const row = page.getByRole('row', { name: /Bhavesh Rao/ });
  await row.getByRole('button', { name: 'Void' }).click();
  await row.getByRole('button', { name: 'Void it' }).click();

  // The refusal is shown as the server worded it, in the row that was asked about.
  await expect(page.getByText(/in a settlement plan or has already been settled/)).toBeVisible();

  // And the optimistic status is rolled back: a row that stayed Voided after the server refused would be this screen
  // inventing a correction that never happened.
  await expect(row.getByRole('cell', { name: 'Open' })).toBeVisible();
  expect(voids).toHaveLength(1);
});

test('a filter is a URL, so a narrowed view can be sent to somebody', async ({ page }) => {
  await stubObligationsApi(page);
  await page.goto(URL);

  await page.getByLabel('Status').selectOption('settled');

  await expect(page).toHaveURL(/status=settled/);
});
