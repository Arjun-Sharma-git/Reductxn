import { expect, test } from '@playwright/test';

import { SHARE, signIn, stubIdentityApi, stubMembersApi, stubSharesApi } from './stubs';

/**
 * The group's members, end to end in a browser: read who is in the group, and record somebody.
 *
 * This is the slice that made a broken flow visible. The client has read `/groups/{groupId}/members` from three
 * screens since obligations were built, and the endpoint did not exist - so every screen that names a person showed a
 * uuid in development, and no suite could see it: the client's tests stubbed the API, and the server's had never asked
 * for members (ADR 0035). The assertions here are about what a member can now see and do, and about the two claims
 * the contract makes that only a screen can honour: that a role is recorded rather than enforced, and that recording
 * the same person twice is one membership.
 *
 * What changed with ADR 0048 is who may do the writing: a member of the group, so the record test signs in and the stub
 * refuses a write without the session's token. The *read* is still open to anybody holding the group's id, which is the
 * half of the identity gap this slice left alone on purpose.
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
  await stubIdentityApi(page);

  // Signed in, because a member of the group is who may add one (ADR 0048) - and the stub refuses the write without the
  // token, so a passing test here is also a test that the client sends it.
  await signIn(page);
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

test('a signed-out visitor cannot record a member, and is told why', async ({ page }) => {
  // No identity stub and no sign-in: the write goes out with no token, so the stub answers 401 the way the API does.
  // The form stays on the screen with the refusal beside it - nobody was added, and the screen does not pretend. The
  // helper is told not to sign in, because it does by default now that the *read* beside the write needs a member too
  // (ADR 0054) - and a signed-in page cannot produce this answer.
  const api = await stubMembersApi(page, undefined, { signedIn: false });

  await page.goto(MEMBERS);
  await page.getByLabel('User id').fill('44444444-4444-4444-8444-444444444444');
  await page.getByLabel('Name').fill('Charu');
  await page.getByRole('button', { name: 'Record the member' }).click();

  await expect(page.getByText(/not signed in/).first()).toBeVisible();
  // Nothing reached the stub's write path: the refusal happened before it, so there is no body to inspect - which is
  // what "nobody was added" means from the client's side. (The screen says this twice: once as a standing warning for a
  // visitor with no session, once as the refusal beside the field, which is why this is `.first()`.)
  expect(api.recorded).toHaveLength(0);
  // And no list either, which is new with ADR 0054: the read beside this write needs a member of the group now, so a
  // signed-out visitor is told that the group's members are not theirs to read rather than being shown them. This line
  // asserted the opposite - that Asha Menon's row was visible - because the read answered anybody then.
  await expect(page.getByRole('cell', { name: 'Asha Menon' })).toHaveCount(0);
});

test('a member makes a link somebody can look through, and it says what it is handing out', async ({ page }) => {
  // The invitation card's sibling: the same act with a different audience (ADR 0055). The share stub refuses a creation
  // without the session's token - a link is something a member hands out - so this spec also proves the token was sent.
  await stubMembersApi(page);
  const shares = await stubSharesApi(page);

  await page.goto(MEMBERS);
  await page.getByRole('button', { name: 'Make a link' }).click();

  await expect(page.getByTestId('share-code')).toHaveText(SHARE.code);
  await expect(page.getByText(/Usable until/)).toBeVisible();
  // The warning is the reason the card exists rather than being decoration: a link is a bearer credential, and the person
  // making one is the only one who can judge whether the group's numbers may travel with it.
  await expect(page.getByText(/Whoever holds this link can read those numbers/)).toBeVisible();
  expect(shares.created).toEqual([`/v1/groups/${GROUP}/shares`]);
});

test('says nothing about a group with no members except how to start', async ({ page }) => {
  await stubMembersApi(page, []);
  await page.goto(MEMBERS);

  await expect(page.getByText('Nobody is a member yet.')).toBeVisible();
});
