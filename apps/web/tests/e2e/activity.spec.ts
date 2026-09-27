import { expect, test } from '@playwright/test';

import { stubActivityApi, stubIdentityApi, type ActivityRow } from './stubs';

/**
 * A group's history, in a real browser.
 *
 * Two things this proves that the API's tests cannot. The feed is rendered by a switch over the contract's enum, so each kind
 * becomes a sentence in the reader's language from structured fields rather than from prose a server composed. And the two
 * kinds that deliberately name nobody say so: an invitation names nobody because the row does, and a decision records who
 * answered without recording what the group calls them - so the screen must not print a name it does not have.
 */

const GROUP_ID = '3f6c1a52-9d4b-4a17-8e2c-5b7d9f0a1c34';

const FEED: readonly ActivityRow[] = [
  {
    kind: 'decision_recorded',
    at: '2026-09-25T10:00:00Z',
    memberId: '8f14e45f-ceea-467a-9e4b-1c2d3e4f5a6b',
    decision: 'approve',
  },
  { kind: 'invitation_created', at: '2026-09-24T09:00:00Z', uses: 1, maxUses: 3 },
  {
    kind: 'member_joined',
    at: '2026-09-23T08:00:00Z',
    memberId: '3f6c1a52-9d4b-4a17-8e2c-5b7d9f0a1c34',
    displayName: 'Rehana',
    role: 'member',
  },
];

test('each kind of event is a sentence, newest first', async ({ page }) => {
  await stubIdentityApi(page);
  await stubActivityApi(page, FEED);

  await page.goto(`/groups/${GROUP_ID}/activity`);

  const rows = page.locator('ol li');

  await expect(rows).toHaveCount(3);
  await expect(rows.nth(0)).toContainText('A member answered a settlement run: approved.');
  await expect(rows.nth(1)).toContainText('spent 1 of 3 times');
  await expect(rows.nth(2)).toContainText('Rehana joined, as a member.');
});

test('a refusal is shown, and a group nobody can see is not rendered as an empty feed', async ({ page }) => {
  await stubIdentityApi(page);
  await stubActivityApi(page, [], { forbidden: true });

  await page.goto(`/groups/${GROUP_ID}/activity`);

  // The sentence the error catalogue carries for the group read's single code, rather than a blank list that would read as
  // "nothing has happened here".
  await expect(page.getByText(/There is no group with that id that you are a member of/)).toBeVisible();
  await expect(page.getByText('Nothing has happened yet.')).toHaveCount(0);
});
