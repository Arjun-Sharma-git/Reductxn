import { expect, test } from '@playwright/test';

import { INVITE, ME, signIn, stubIdentityApi, stubInvitesApi, stubMembersApi } from './stubs';

/**
 * The invite flow in a real browser: making a code, and spending one.
 *
 * Two screens, because the two halves belong to different people: the group makes a code on its members screen, and the
 * person arriving lands on a page of its own (ADR 0041).
 *
 * The refusals get a test each, and they are the reason there are two codes under one status: "that invitation expired"
 * and "that code does not exist" are different things to be told, and a client that showed one sentence for both would
 * be telling somebody to ask for a new code when they had simply mistyped one.
 *
 * What changed with ADR 0047 is the second half's identity: the acceptance is about the caller, so these tests sign in
 * first and the stub refuses an acceptance without the session's token. The body they send is a name and nothing else,
 * which is the contract's own promise kept rather than a tidier version of it.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const MEMBERS_URL = `/groups/${GROUP}/members`;
const CODE = 'JKM2345P';

test('the members screen makes a code and says what it is handing out', async ({ page }) => {
  await stubMembersApi(page);
  await stubInvitesApi(page);
  await stubIdentityApi(page);

  // Signed in, because a member of the group is who may create a code (ADR 0048): the stub refuses the creation without
  // the session's token, so this test also says the client sent one.
  await signIn(page);

  await page.goto(MEMBERS_URL);
  await page.getByRole('button', { name: 'Make a code' }).click();

  await expect(page.getByTestId('invite-code')).toHaveText(INVITE.code);
  // The expiry and the absence of a limit are both said, because both are what a reader has to know before sending it.
  await expect(page.getByText(/Usable until/)).toBeVisible();
  await expect(page.getByText(/no limit on how many times/)).toBeVisible();
});

test('accepting a code joins the group as the account that is signed in', async ({ page }) => {
  const api = await stubInvitesApi(page);
  await stubIdentityApi(page);

  // Signed in first, because the acceptance is about the caller: with no session there is nobody to join, and the stub
  // refuses it the way the API does (ADR 0047).
  await signIn(page);
  await page.goto(`/invites/${CODE}`);

  // The code is on the screen, so somebody who followed a link can check it is the one they were sent.
  await expect(page.getByTestId('invite-code')).toHaveText(CODE);

  // And so is the group it is for, which is the read ADR 0054 added: before it, this page asked for a name before it
  // could say what the name was for.
  await expect(page.getByText('You are joining Flat 3B.')).toBeVisible();
  await expect(page.getByText('4 members')).toBeVisible();
  await expect(page.getByText(/Usable until/)).toBeVisible();

  const join = page.getByRole('button', { name: 'Join the group' });
  await expect(join).toBeDisabled();

  await page.getByLabel(/Your name/).fill('Alice Rao');
  await join.click();

  await expect(page.getByText('You are in')).toBeVisible();
  await expect(page.getByText('Alice Rao is a member of this group.')).toBeVisible();

  // The id shown is the signed-in account's, and it is the id the *server* decided on - the body carries a name and
  // nothing else, which is the contract's promise kept rather than a tidier version of it.
  await expect(page.getByTestId('member-id')).toHaveText(ME.id);
  expect(api.acceptances).toHaveLength(1);
  expect(api.acceptances[0]).toBe('{"displayName":"Alice Rao"}');

  // And a way back to what was joined, which the acceptance alone could not provide: its response is a member, and a
  // member carries no group. The group id comes from the preview.
  await page.getByRole('link', { name: 'Go to the group' }).click();
  await expect(page).toHaveURL(new RegExp(`/groups/${GROUP}/netting$`));
});

test('a code for a group that has no name yet still says which group it is', async ({ page }) => {
  // A group that exists only as rows: `group_members` and `invitations` write a bare uuid, so its codes are real and the
  // preview's `name` is absent rather than empty (ADR 0043, 0054).
  await stubInvitesApi(page, { group: { id: GROUP, name: null, memberCount: 2 } });
  await stubIdentityApi(page);

  await page.goto(`/invites/${CODE}`);

  await expect(page.getByText(/no name yet/)).toBeVisible();
  await expect(page.getByText(GROUP)).toBeVisible();
  await expect(page.getByText('2 members')).toBeVisible();
});

test('a code reads without a session, which is what the operation is for', async ({ page }) => {
  // No identity stub and no sign-in at all: the preview is the one operation a person who has not signed in can read,
  // because they are the person a link is sent to. The stub does not check for a token either, so this test would fail
  // against a stub that demanded one - which is the point of asserting it here rather than in the API's own tests.
  await stubInvitesApi(page);

  await page.goto(`/invites/${CODE}`);

  await expect(page.getByText('You are joining Flat 3B.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Join the group' })).toBeVisible();
});

test('an acceptance with no session is refused rather than joined', async ({ page }) => {
  // No identity stub and no sign-in, so the request goes out with no token and the stub answers 401 exactly as the API
  // would. The screen shows the refusal and stays on the form - there is no membership to claim.
  await stubInvitesApi(page);

  await page.goto(`/invites/${CODE}`);
  await page.getByLabel(/Your name/).fill('Alice Rao');
  await page.getByRole('button', { name: 'Join the group' }).click();

  await expect(page.getByText(/not signed in/)).toBeVisible();
  await expect(page.getByText('You are in')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Join the group' })).toBeVisible();
});

/**
 * A code that has run out is refused **before anybody types a name**, and the two ways it can run out are told apart.
 *
 * <p>These two tests used to submit the form and assert the refusal beside it, which is what the screen could do when the
 * only operation behind a code was the acceptance. The preview answers the same three conditions one step earlier (ADR
 * 0054), so the form is not rendered at all - and that is the improvement rather than a detail: a stale link should tell
 * somebody to ask for a new one, not collect their name and then tell them.
 */
test('an expired invitation says it ran out of time, before the form appears', async ({ page }) => {
  await stubInvitesApi(page, { refusal: { status: 410, code: 'api.invite_expired' } });
  await stubIdentityApi(page);
  await signIn(page);

  await page.goto(`/invites/${CODE}`);

  await expect(page.getByText(/That invitation has expired/)).toBeVisible();
  // Nothing to fill in: there is no membership to claim and no name worth asking for.
  await expect(page.getByRole('button', { name: 'Join the group' })).toBeHidden();
});

test('a code nobody holds says the code is wrong rather than that it ran out', async ({ page }) => {
  await stubInvitesApi(page, { refusal: { status: 404, code: 'api.no_such_invite' } });
  await stubIdentityApi(page);
  await signIn(page);

  await page.goto('/invites/ZZZZ9999');

  await expect(page.getByText(/That invite code does not exist/)).toBeVisible();
  await expect(page.getByText(/has expired/)).toBeHidden();
  await expect(page.getByRole('button', { name: 'Join the group' })).toBeHidden();
});
