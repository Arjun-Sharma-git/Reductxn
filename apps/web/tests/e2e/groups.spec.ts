import { expect, test } from '@playwright/test';

import { group, signIn, stubGroupsApi, stubIdentityApi } from './stubs';

/**
 * The way in, in a real browser: the groups you are in, making one, and what a signed-out visitor is shown.
 *
 * Three things this proves that no unit test can. The list is rendered from `GET /groups` with the person's session on it -
 * the stub answers `401` to anything else, so a header that shows a group has proved the token was sent. Creating a group
 * carries an `Idempotency-Key`, which is the header the contract requires and the property that makes a retry safe. And a
 * visitor who is not signed in is told how to sign in rather than shown an empty list with no explanation.
 *
 * The `signIn` helper moved to `stubs.ts` when the invite acceptance began to need a caller too: four specs sign in now,
 * and the two steps are the same two steps in each of them (ADR 0047).
 */

const GROUP_ID = '3f6c1a52-9d4b-4a17-8e2c-5b7d9f0a1c34';

test('a signed-out visitor is shown the way in, not an empty list', async ({ page }) => {
  // No identity stub at all: every request to `/v1/me` and `/v1/groups` fails at the network, which is what a signed-out
  // browser looks like from the screen's side.
  await stubGroupsApi(page);

  await page.goto('/');

  await expect(page.getByText('Sign in to see the groups you belong to.')).toBeVisible();
  // The paste box still works, because opening a group by id never needed to know who was asking.
  await expect(page.getByLabel('Group id')).toBeVisible();
});

test('the groups you are in are listed with what they settle in', async ({ page }) => {
  await stubIdentityApi(page);
  await stubGroupsApi(page, [group(GROUP_ID, 'Flat 3B', 'INR', 3)]);

  await signIn(page);
  await page.goto('/');

  await expect(page.getByRole('link', { name: 'Flat 3B' })).toBeVisible();
  await expect(page.getByText('INR · 3 members')).toBeVisible();
});

test('making a group sends a name, a currency, and an idempotency key', async ({ page }) => {
  await stubIdentityApi(page);

  const api = await stubGroupsApi(page);

  await signIn(page);
  await page.goto('/');

  // The button is refused until the form can be sent: a name and a currency, because the server needs both and the
  // catalogue is what offers them.
  await expect(page.getByRole('button', { name: 'Make the group' })).toBeDisabled();

  await page.getByLabel('Name').fill('  Flat 3B  ');
  await page.getByLabel('Settles in').selectOption('INR');

  await expect(page.getByRole('button', { name: 'Make the group' })).toBeEnabled();
  await page.getByRole('button', { name: 'Make the group' }).click();

  // What the server was asked: the trimmed name, the chosen currency, and the header the contract requires (P8).
  await expect(page.getByRole('link', { name: 'Flat 3B' })).toBeVisible();
  expect(api.created).toHaveLength(1);
  expect(api.created[0]?.body).toEqual({ name: 'Flat 3B', currency: 'INR' });
  expect(api.created[0]?.key).toBeTruthy();
});
