import { fileURLToPath } from 'node:url';

import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * The unit and component tests.
 *
 * Separate from `vite.config.ts` deliberately: the router plugin generates a route tree as a build side effect,
 * and a test run should not depend on that having happened - a component test renders the component it names,
 * not the router's opinion of the filesystem.
 *
 * `jsdom` rather than a real browser, because what is unit-tested here is arithmetic (money formatting and
 * parsing), catalog loading and one screen's markup. The things a DOM cannot tell you - focus rings, contrast,
 * reduced-motion behaviour - are checked in the Playwright suite, in a real browser, which is the only place
 * those questions have answers.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'jsdom',
    globals: false,
    include: ['src/**/*.test.{ts,tsx}', 'tests/**/*.test.{ts,tsx}'],
    setupFiles: ['tests/setup.ts'],
  },
});
