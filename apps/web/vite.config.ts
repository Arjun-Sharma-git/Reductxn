import { fileURLToPath } from 'node:url';

import { tanstackRouter } from '@tanstack/router-plugin/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * The build for the browser client.
 *
 * Two plugins: the router plugin generates the route tree from `src/routes` (the filesystem is the route
 * definition, so a screen cannot exist without a route and a route cannot exist without a file), and React's.
 *
 * The dev server proxies `/v1` to the API rather than the client holding a cross-origin base URL. That is what
 * the deployment looks like - one origin, a reverse proxy in front of both - and it means the client's code has
 * no environment-specific URL, no CORS in development, and no cookie or header behaviour that differs between
 * the two.
 */
export default defineConfig({
  plugins: [tanstackRouter({ target: 'react', autoCodeSplitting: true }), react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/v1': {
        target: process.env.REDUCTXN_API_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Source maps in the artefact: a stack trace from a settlement screen that names only minified functions is
    // a report nobody can act on.
    sourcemap: true,
  },
});
