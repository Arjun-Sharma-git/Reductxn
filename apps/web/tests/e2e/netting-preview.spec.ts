import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { EMPTY_PREVIEW, stubApi } from './stubs';

/**
 * The preview screen in a real browser, and the five things a browser is needed for.
 *
 * Everything here is a claim the *skill* makes a priority, which is why they are tests rather than intentions:
 * contrast is measured with axe (its first category), focus is visible (its first category), the reduced-motion
 * branch exists (its second and seventh), the money is rendered per currency and never summed (ADR 0012, and the
 * P1 boundary the client shares with the server), and the review-state rule from `docs/specs/i18n.md` section 8
 * actually changes what a member reads.
 */

const GROUP = '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10';
const PREVIEW_URL = `/groups/${GROUP}/netting`;

test('renders every currency the plan has, with the digits the server sent', async ({ page }) => {
  await stubApi(page);
  await page.goto(PREVIEW_URL);

  // The browser's own locale list resolves to `en`, and its grouping is thousands: 12,345,678 minor units of INR
  // is 123,456.78. (An `en-IN` reader gets the lakh grouping, which the unit tests cover - the client's locale is
  // whatever the reader's browser asks for, not a constant.)
  // Several cells carry an INR amount on this screen - two positions and two transfers - so the first match is
  // the assertion; what it proves is that the *server's integer*, grouped the reader's way, is what is rendered.
  await expect(page.getByRole('cell', { name: '₹123,456.78' }).first()).toBeVisible();
  // 123,457 yen has no minor unit at all, so no decimal separator appears.
  await expect(page.getByRole('cell', { name: '¥123,457' }).first()).toBeVisible();
});

test('names the currency clearing leg rather than hiding it in a member’s transfer', async ({ page }) => {
  await stubApi(page);
  await page.goto(PREVIEW_URL);

  await expect(page.getByRole('cell', { name: /Currency clearing/ })).toBeVisible();
});

test('says a group with nothing open has nothing to settle', async ({ page }) => {
  await stubApi(page, { preview: EMPTY_PREVIEW });
  await page.goto(PREVIEW_URL);

  await expect(page.getByText('This group has no open commitments, so there is nothing to settle.')).toBeVisible();
  await expect(page.getByRole('table')).toHaveCount(0);
});

test('falls back to English with a notice when the locale has not been reviewed', async ({ page }) => {
  await stubApi(page);
  // Russian is seeded and unreviewed, and this screen shows what a member will pay - so the app refuses it, which
  // is the rule from docs/specs/i18n.md section 8.4. The URL still carries the reader's choice.
  await page.goto(`${PREVIEW_URL}?lang=ru`);

  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.getByText(/has not been reviewed/)).toBeVisible();
});

test('the language switch is a navigation, so a shared link opens in the sharer’s language', async ({ page }) => {
  await stubApi(page);
  await page.goto(PREVIEW_URL);

  await page.getByLabel('Language').selectOption('ru');

  await expect(page).toHaveURL(/lang=ru/);
});

test('keyboard focus is visible rather than removed', async ({ page }) => {
  await stubApi(page);
  await page.goto(PREVIEW_URL);

  // The shell has to have rendered before the tab order means anything: a first Tab against an empty document
  // lands on `body`, which is what this test asserted the first time it ran.
  await expect(page.getByRole('heading', { name: 'Netting preview' })).toBeVisible();

  await page.keyboard.press('Tab');

  const focused = await page.evaluate(() => {
    const element = document.activeElement;

    return element === null
      ? null
      : {
          tag: element.tagName,
          text: element.textContent,
          outlineStyle: getComputedStyle(element).outlineStyle,
          outlineWidth: getComputedStyle(element).outlineWidth,
        };
  });

  // The first tab stop is the skip link, and focusing it brings it on screen - which is the whole reason it
  // exists (a keyboard user should not have to tab through the header to reach the report).
  expect(focused?.tag).toBe('A');
  expect(focused?.outlineStyle).toBe('solid');
});

test('reduced motion means the plan is simply there, with no entrance animation', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await stubApi(page);
  await page.goto(PREVIEW_URL);

  const row = page.locator('tbody tr').first();
  await expect(row).toBeVisible();

  // GSAP writes inline transforms; under reduced motion it must not have touched the element at all.
  expect(await row.evaluate((element) => getComputedStyle(element).transform)).toBe('none');
  expect(await row.evaluate((element) => element.getAttribute('style'))).toBeNull();
});

test('has no accessibility violations, contrast included', async ({ page }) => {
  await stubApi(page);
  await page.goto(PREVIEW_URL);
  await expect(page.getByRole('cell', { name: '₹123,456.78' }).first()).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
    .analyze();

  expect(results.violations.map((violation) => `${violation.id}: ${violation.nodes.length}`)).toEqual([]);
});
