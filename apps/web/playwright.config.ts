import { defineConfig, devices } from '@playwright/test';

/**
 * The browser suite: the questions jsdom cannot answer.
 *
 * Focus rings, computed contrast, reduced-motion behaviour and a locale switch that is a real navigation are all
 * *browser* facts. A unit test can assert that a class is applied; only a browser can say whether the outline is
 * visible against the background it landed on.
 *
 * The API is stubbed per test (`page.route`) rather than run for real. That is a decision with a cost - the stub
 * could drift from the contract - and it is paid for twice: the responses are typed by the generated schema
 * (`stubs.ts`), so a field the contract does not have is a compile error, and the *server* side of every endpoint
 * has container tests against a real Postgres. What this suite is for is the client's behaviour given a response,
 * not the response itself.
 *
 * `preview` serves the built artefact, not the dev server: a test that passes against `vite dev` has not tested
 * the thing a deployment serves.
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] === undefined ? 0 : 1,
  reporter: process.env['CI'] === undefined ? 'list' : [['github'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'pnpm build && pnpm exec vite preview --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: process.env['CI'] === undefined,
    timeout: 120_000,
  },
});
