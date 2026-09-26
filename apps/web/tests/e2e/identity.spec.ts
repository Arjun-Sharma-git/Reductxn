import { expect, test } from '@playwright/test';

import { ME, stubIdentityApi } from './stubs';

/**
 * Identity in a real browser: asking for a code, handing it back, and being somebody afterwards.
 *
 * Three things this proves end to end that no unit test can: the header stops offering a sign-in link once there is a
 * session, the token the client stored is the token it sends (`/v1/me` refuses anything else, so a header that renders a
 * name has proved it), and a refused code shows the sentence the server's code maps to.
 */

const PHONE = '+919812345678';

test('signing in makes the header say who you are', async ({ page }) => {
  const api = await stubIdentityApi(page);

  await page.goto('/sign-in');
  // A signed-out visitor is offered the way in rather than told they are not signed in.
  await page.getByLabel('Phone number').fill(PHONE);
  await page.getByRole('button', { name: 'Send me a code' }).click();

  // The second step, and the number it was sent to: the code goes to the server log in this deployment, and the screen
  // says so rather than telling somebody to check their messages.
  await expect(page.getByText(/A code was sent to/)).toBeVisible();
  await expect(page.getByText(/server log/)).toBeVisible();

  await page.getByLabel('Code').fill('481516');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByText(`Signed in as ${ME.displayName}`)).toBeVisible();

  // What the client sent, and that it sent the token it was given: the number, the code, and the device label.
  expect(api.requested()).toBe(1);
  expect(api.verified).toHaveLength(1);
  expect(api.verified[0]).toContain('"code":"481516"');
  expect(api.verified[0]).toContain('"deviceId":"web"');
});

test('a code the deployment will not accept is said in the reader\'s own words', async ({ page }) => {
  await stubIdentityApi(page, { refusal: { status: 401, code: 'api.otp_not_accepted' } });

  await page.goto('/sign-in');
  await page.getByLabel('Phone number').fill(PHONE);
  await page.getByRole('button', { name: 'Send me a code' }).click();

  await page.getByLabel('Code').fill('000000');
  await page.getByRole('button', { name: 'Sign in' }).click();

  // The catalogue's sentence, not the problem document's `detail`: the code is what a client branches on (RULE B20).
  await expect(page.getByText(/That code is not one this deployment will accept/)).toBeVisible();
  await expect(page.getByLabel('Code')).toBeVisible();
});

test('the code field refuses what could not be a code, without asking the server', async ({ page }) => {
  const api = await stubIdentityApi(page);

  await page.goto('/sign-in');
  await page.getByLabel('Phone number').fill(PHONE);
  await page.getByRole('button', { name: 'Send me a code' }).click();

  await page.getByLabel('Code').fill('12345');

  await expect(page.getByRole('button', { name: 'Sign in' })).toBeDisabled();

  // Five digits never became a request, which is the point: the server answers the same refusal for four different
  // reasons, so a client that sent this would learn nothing.
  expect(api.verified).toHaveLength(0);
});

test('going back to a different number is a step, not a reload', async ({ page }) => {
  await stubIdentityApi(page);

  await page.goto('/sign-in');
  await page.getByLabel('Phone number').fill(PHONE);
  await page.getByRole('button', { name: 'Send me a code' }).click();

  await page.getByRole('button', { name: 'Use a different number' }).click();

  await expect(page.getByLabel('Phone number')).toHaveValue(PHONE);
  await expect(page.getByLabel('Code')).toBeHidden();
});
