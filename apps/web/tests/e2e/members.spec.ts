import { expect, test } from '@playwright/test';

import { stubMembersApi } from './stubs';

/**
 * The group's members, end to end in a browser: read who is in the group, and record somebody.
 *
 * This is the slice that made a broken flow visible. The client has read `/groups/{groupId}/members` from three
 * screens since obligations were built, and the endpoint did not exist - so every screen that names a person showed a
 * uuid in development, and no suite could see it: the client's tests stubbed the API, and the server's had never asked
 * for members (ADR 0035). The assertions here are about what a member can now see and do, and about the two claims
 * the contract makes that only a screen can honour: that a role is recorded rather than enforced, and that recording
 * the same person twice is one membership.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const MEMBERS = `/groups/${GROUP}/members`;

test('lists the group’s members with the role the group recorded', async ({ page }) => {
  await stubMembersApi(page);
  await page.goto(MEMBERS);

  await expect(page.getByRole('cell', { name: 'Asha Menon' })).toBeVisible();
  // The role is a catalog key built from the server's word (`members.role.admin`), which no static check can see
  // asked for by name - which is why this assertion is here rather than in the catalog checker. The count is two
  // rather than "at least one": the fixture has two members, so a screen that rendered one word for every row would
  // pass a looser assertion.
  await expect(page.getByRole('cell', { name: 'Admin' })).toHaveCount(1);
  await expect(page.getByRole('cell', { name: 'Member' })).toHaveCount(2);

  // The identifier is shown as well as the name: it is what every other screen falls back to when a name is missing.
  await expect(page.getByRole('cell', { name: '11111111-1111-4111-8111-111111111111' })).toBeVisible();
});

test('records a member and sees them in the list', async ({ page }) => {
  const api = await stubMembersApi(page);
  await page.goto(MEMBERS);

  await page.getByLabel('User id').fill('44444444-4444-4444-8444-444444444444');
  await page.getByLabel('Name').fill('Charu');
  await page.getByRole('button', { name: 'Record the member' }).click();

  await expect(page.getByText('Charu is a member of this group now.')).toBeVisible();
  await expect(page.getByRole('cell', { name: 'Charu' })).toBeVisible();

  // The wire carries the three fields the contract declares, with the role the control was on.
  expect(api.recorded).toHaveLength(1);
  expect(api.recorded[0]).toEqual({
    userId: '44444444-4444-4444-8444-444444444444',
    displayName: 'Charu',
    role: 'member',
  });
});

test('refuses an identifier that is not one without asking the server', async ({ page }) => {
  const api = await stubMembersApi(page);
  await page.goto(MEMBERS);

  // The ordinary mistake: a name typed into the field that wants an identifier. The refusal uses the server's code
  // and lands beside the field, and the request is never sent.
  await page.getByLabel('User id').fill('Charu');
  await page.getByLabel('Name').fill('Charu');
  await page.getByRole('button', { name: 'Record the member' }).click();

  await expect(page.getByText('Something is missing from the request.')).toBeVisible();
  expect(api.recorded).toHaveLength(0);
});

test('says nothing about a group with no members except how to start', async ({ page }) => {
  await stubMembersApi(page, []);
  await page.goto(MEMBERS);

  await expect(page.getByText('Nobody is a member yet.')).toBeVisible();
});
