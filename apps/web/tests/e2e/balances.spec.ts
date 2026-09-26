import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { NO_BALANCES, stubApi, stubMembersApi } from './stubs';

/**
 * The balances screen in a real browser.
 *
 * Three of these could only be browser tests: the amount rendered with the reader's grouping and the currency's own
 * exponent, the direction as a *word* that a screen reader announces and a monochrome reader can still read, and the
 * footnote that appears only when a row has no name. The fourth is the navigation, which is the only way a reader
 * reaches this screen at all - a route nothing links to is a page nobody has.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const BALANCES_URL = `/groups/${GROUP}/balances`;

/** Both stubs, because the screen needs the positions and the names to show them by. */
async function openBalances(page: Parameters<typeof stubApi>[0], balances?: Parameters<typeof stubApi>[1]) {
  await stubApi(page, balances);
  await stubMembersApi(page);
  await page.goto(BALANCES_URL);
}

test('shows what each member owes, by name and per currency', async ({ page }) => {
  await openBalances(page);

  // The router's first render is asynchronous, so the table's presence is what the assertions below depend on.
  await expect(page.getByRole('heading', { name: 'Balances' })).toBeVisible();

  // 12,345,678 minor units of INR is 123,456.78 in the browser's `en` grouping, and 123,457 yen has no minor unit at
  // all - the same two amounts the preview screen asserts, because it is the same position.
  await expect(page.getByRole('cell', { name: '₹123,456.78' }).first()).toBeVisible();
  await expect(page.getByRole('cell', { name: '¥123,457' }).first()).toBeVisible();

  // Names, not identifiers: this is the screen the members read made possible (ADR 0035, ADR 0039).
  await expect(page.getByRole('rowheader', { name: 'Asha Menon' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Bhavesh Rao' })).toBeVisible();

  // Direction is a word rather than a sign or a colour.
  await expect(page.getByRole('cell', { name: 'Pays' }).first()).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Settled up' }).first()).toBeVisible();
});

test('shows an identifier rather than guessing, and says what it might be', async ({ page }) => {
  await openBalances(page);

  // The clearing account is not a member and the schema cannot label it, and the fixture's fourth position belongs to
  // somebody the member list does not contain - both are shown by identifier, and the footnote names both cases
  // rather than picking one.
  await expect(page.getByRole('rowheader', { name: '99999999-9999-4999-8999-999999999999' })).toBeVisible();
  await expect(page.getByRole('rowheader', { name: '44444444-4444-4444-8444-444444444444' })).toBeVisible();
  await expect(page.getByText(/clearing account/)).toBeVisible();
});

test('says a group with nothing open has nothing outstanding', async ({ page }) => {
  await openBalances(page, { balances: NO_BALANCES });

  await expect(page.getByText('Nothing is outstanding')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('is reachable from the group’s navigation, with the group it was opened for', async ({ page }) => {
  await openBalances(page);

  await page.getByRole('link', { name: 'Balances' }).click();

  await expect(page).toHaveURL(new RegExp(`${BALANCES_URL}$`));
  await expect(page.getByRole('heading', { name: 'Balances' })).toBeVisible();
});

test('has no accessibility violations, contrast included', async ({ page }) => {
  await openBalances(page);
  await expect(page.getByRole('cell', { name: '₹123,456.78' }).first()).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  expect(results.violations.map((violation) => `${violation.id}: ${violation.nodes.length}`)).toEqual([]);
});
