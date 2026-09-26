import { expect, test } from '@playwright/test';

import { TAX_REPORT_EMPTY, stubTaxApi } from './stubs';

/**
 * The tax screen in a real browser.
 *
 * The assertions are about the three things only a browser can show: money rendered with each currency's own exponent,
 * a rule's parameters rendered as the pairs the server sent rather than translated into a percentage this client was
 * never told the unit of, and a period that becomes a *request* - recorded by the stub - rather than only a URL.
 *
 * The last test is the one worth keeping: a range that ends before it starts must not be asked at all.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const TAX_URL = `/groups/${GROUP}/tax`;

test('shows the rules in force with their parameters and the statute each encodes', async ({ page }) => {
  await stubTaxApi(page);
  await page.goto(TAX_URL);

  await expect(page.getByRole('heading', { name: 'Tax' })).toBeVisible();

  // The shape is a word rather than the enum's own name: `flat` is a column value and "Flat charge per transfer" is
  // what a reader is being told.
  await expect(page.getByRole('cell', { name: 'Flat charge per transfer' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'A percentage of the amount' })).toBeVisible();

  // Both currencies and both statutes, one of which has no end.
  await expect(page.getByRole('cell', { name: 'FY2020 s.7(3)' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'FY2024 s.9(1)' })).toBeVisible();
  await expect(page.getByRole('cell', { name: 'No end' })).toBeVisible();

  // The parameters are shown as the pairs they are: 50 minor units per transfer, and a basis-point rate this client
  // deliberately does not turn into a percentage.
  await expect(page.getByText('perTransferMinor: 50')).toBeVisible();
  await expect(page.getByText(/rateBps: 150/)).toBeVisible();
});

test('shows what the plans assess, per currency, and never as one total', async ({ page }) => {
  await stubTaxApi(page);
  await page.goto(TAX_URL);

  // 550 minor units of INR is ₹5.50 and 150 yen has no minor unit at all - the saving netting made in each currency.
  await expect(page.getByRole('cell', { name: '₹5.50' }).first()).toBeVisible();
  await expect(page.getByRole('cell', { name: '¥150' }).first()).toBeVisible();

  // One row per currency and no third row summing them.
  await expect(page.getByRole('rowheader', { name: 'INR' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'JPY' })).toBeVisible();
  await expect(page.getByText(/Nothing on this screen is added up across currencies/)).toBeVisible();
});

test('a period is a question the screen asks the server, and it survives a reload', async ({ page }) => {
  const api = await stubTaxApi(page);
  await page.goto(TAX_URL);

  await page.getByLabel('From').fill('2026-09-01');
  await page.getByLabel('To').fill('2026-09-30');
  await page.getByRole('button', { name: 'Apply' }).click();

  // The URL carries the question, so it can be shared...
  await expect(page).toHaveURL(/periodStart=2026-09-01/);
  await expect(page).toHaveURL(/periodEnd=2026-09-30/);

  // ...and the server was asked it, which is the part a URL assertion cannot see.
  expect(api.reportRequests.some((query) => query.includes('periodStart=2026-09-01'))).toBe(true);

  // A reload of the shared link asks the same question rather than starting over.
  await page.reload();
  await expect(page.getByLabel('From')).toHaveValue('2026-09-01');
  expect(api.reportRequests.length).toBeGreaterThan(1);
});

test('a range that ends before it starts is not asked at all', async ({ page }) => {
  const api = await stubTaxApi(page);
  await page.goto(TAX_URL);

  await page.getByLabel('From').fill('2026-09-30');
  await page.getByLabel('To').fill('2026-09-01');

  await expect(page.getByText(/That range ends before it starts/)).toBeVisible();
  await expect(page.getByRole('button', { name: 'Apply' })).toBeDisabled();

  const before = api.reportRequests.length;
  await page.waitForTimeout(200);

  expect(api.reportRequests.length).toBe(before);
});

test('says a period with no plan in it has nothing to report', async ({ page }) => {
  await stubTaxApi(page, { report: TAX_REPORT_EMPTY });
  await page.goto(TAX_URL);

  await expect(page.getByText(/No plan has been computed in that period/)).toBeVisible();
});
