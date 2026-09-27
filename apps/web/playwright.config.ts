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
  /*
   * A fifteen-second ceiling on an assertion, rather than the five-second default.
   *
   * This is about a cold start, not about patience. The first run of this suite against a freshly built clone had
   * three netting-preview tests fail at the default 5s and pass on an immediate re-run: `vite preview` compiles and
   * serves the bundle on the first request, and a cold machine can take longer to answer that first request than a
   * warm one - so the timeout was measuring the machine, not the screen. A longer ceiling does not hide a real
   * failure (a wrong assertion still fails, four seconds later); it removes a source of noise that only appears
   * where the suite matters most, which is a fresh checkout in the pipeline (`.github/workflows/ci.yml`, ADR 0031).
   * This comment said the pipeline did not exist yet, written a slice before it did.
   */
  expect: { timeout: 15_000 },
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
    /*
     * Locally this reuses a server that is already listening, and that has a trap in it worth stating: `vite
     * preview` serves the `dist` directory, so a *failed* build leaves the previous one in place and the suite then
     * tests the last artefact that built. The suite is run from `pnpm e2e`, always after `pnpm build` (or `pnpm
     * check`, which builds), so the artefact is current in practice - and this is one of the things a CI pipeline
     * would remove the possibility of by starting from nothing every time. There is no CI configuration in this
     * repository yet; MASTER-PLAN records that as a gap.
     */
    command: 'pnpm build && pnpm exec vite preview --port 4173 --strictPort',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: process.env['CI'] === undefined,
    timeout: 120_000,
  },
});
