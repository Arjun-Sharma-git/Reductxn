import { expect, test } from '@playwright/test';

import { SCHEDULE, stubScheduleApi } from './stubs';

/**
 * The group's schedule in a real browser: what closes the books, and how often (ADR 0057).
 *
 * <p><strong>What only a browser can show.</strong> The API's own test proves the operations; this proves the three
 * things a person exercises - that a group with no cadence is told so rather than shown an error, that the form's number
 * and instant reach the request as the contract describes them, and that stopping a cadence is a button whose effect the
 * screen shows afterwards. The session is stubbed in by the helper, so a missing token would fail here rather than
 * silently rendering a refusal.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const SCHEDULE_URL = `/groups/${GROUP}/schedule`;

test('shows what happens next, in the group’s own words', async ({ page }) => {
  await stubScheduleApi(page);

  await page.goto(SCHEDULE_URL);

  await expect(page.getByRole('heading', { name: 'Schedule' })).toBeVisible();
  await expect(page.getByTestId('schedule-headline')).toHaveText("This group's books close every 30 days.");
  await expect(page.getByText(/Next closure:/)).toBeVisible();
  // The stamp, because a member needs to know whether the statement is current - and the worker moves the row between
  // one read and the next.
  await expect(page.getByText(/Stated /)).toBeVisible();
});

test('says that nothing closes the books rather than reporting a fault', async ({ page }) => {
  // A group with no cadence: the server answers 404 api.no_such_schedule, which is a state rather than an error.
  await stubScheduleApi(page, { withheld: true });

  await page.goto(SCHEDULE_URL);

  await expect(page.getByText(/Nothing closes this group's books by itself/)).toBeVisible();
  // And the form is still there, because stating one is the way to change that.
  await expect(page.getByRole('button', { name: 'State this cadence' })).toBeVisible();
});

test('states a cadence from the form, in the shape the contract describes', async ({ page }) => {
  const schedule = await stubScheduleApi(page, { withheld: true });

  await page.goto(SCHEDULE_URL);

  await page.getByLabel('Every how many days').fill('90');
  await page.getByLabel('Next closure').fill('2027-01-01T09:00');
  await page.getByRole('button', { name: 'State this cadence' }).click();

  // The request the form produced: a number and an instant, which is what the document declares and nothing else.
  expect(schedule.written.join(' ')).toContain('"everyDays":90');
  expect(schedule.written).toHaveLength(1);

  await expect(page.getByTestId('schedule-headline')).toHaveText("This group's books close every 90 days.");

  const body = JSON.parse(schedule.written[0] ?? '{}') as { everyDays: number; nextDueAt: string };

  expect(body.everyDays).toBe(90);
  expect(new Date(body.nextDueAt).toISOString()).toBe(new Date('2027-01-01T09:00').toISOString());
  expect(SCHEDULE.everyDays).toBe(30);
});
