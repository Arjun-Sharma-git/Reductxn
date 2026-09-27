import { expect, test } from '@playwright/test';

import { SHARE, stubSharesApi } from './stubs';

/**
 * A share link in a real browser: what a group owes, read by somebody who is not in it (ADR 0055).
 *
 * <p><strong>Nothing here signs in, and that is the assertion.</strong> The other group screens are read by members, and
 * their specs sign the page in because the reads require it (ADR 0054); this one is read by whoever holds a link -
 * usually a person with no account in this deployment at all - so a spec that signed in would be testing a flow the
 * product does not have. The stub refuses the *creation* without a token and never checks the read, which is the same
 * asymmetry a deployment has.
 *
 * <p><strong>What it proves that the API's tests cannot:</strong> that the numbers arrive with the right scale in the
 * reader's locale, that a row the group did not name shows the identifier and the footnote rather than a guess, and that
 * the screen says when the figures were computed - which is the one read where the reader's "now" and the server's can be
 * days apart.
 */

const CODE = SHARE.code;
const SHARED_URL = `/shared/${CODE}`;

test('a link shows the group’s positions to somebody with no session', async ({ page }) => {
  await stubSharesApi(page);

  await page.goto(SHARED_URL);

  await expect(page.getByRole('heading', { name: 'What this group owes' })).toBeVisible();
  // The group's name, which the link answers because the reader cannot call the group read.
  await expect(page.getByRole('heading', { name: 'Flat 3B' })).toBeVisible();

  // The names the group recorded, and the amount grouped the reader's way from the server's integer.
  await expect(page.getByRole('rowheader', { name: 'Asha Menon' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '₹123,456.78' }).first()).toBeVisible();
  await expect(page.getByRole('rowheader', { name: 'Bhavesh Rao' })).toBeVisible();

  // The row the group does not name: the identifier, and the footnote that says which two things it might be.
  await expect(page.getByRole('rowheader', { name: '99999999-9999-4999-8999-999999999999' })).toBeVisible();
  await expect(page.getByText(/currency clearing account/)).toBeVisible();

  // And when the numbers were true, because a link can be opened days after it was made.
  await expect(page.getByText(/Computed at/)).toBeVisible();

  // Nothing about a group's history or its commitments, which is the disclosure the link does not make: no group
  // navigation, and one table with the three columns a position has. (The assertion is on the *table* rather than on the
  // word "memo", because the lede above names what the link does not show.)
  await expect(page.getByRole('navigation', { name: 'This group' })).toHaveCount(0);
  await expect(page.getByRole('table')).toHaveCount(1);
  await expect(page.getByRole('columnheader')).toHaveCount(3);
  await expect(page.getByRole('columnheader', { name: 'Memo' })).toHaveCount(0);
});

test('a link that is actually an invitation says what the code is for', async ({ page }) => {
  // The refusal names the true condition rather than pretending the code does not exist, because the person asking holds
  // it (ADR 0055). 422 rather than 404, and this is the sentence the catalog renders for it.
  await stubSharesApi(page, { refusal: { status: 422, code: 'api.invite_not_a_share' } });

  await page.goto(SHARED_URL);

  await expect(page.getByText(/invites somebody to join the group/)).toBeVisible();
  await expect(page.getByRole('rowheader')).toHaveCount(0);
});