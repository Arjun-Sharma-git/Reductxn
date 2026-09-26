import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from '@tanstack/react-router';

import { createAppRouter } from '@/app/router';
import { AppProviders, createQueryClient } from '@/app/providers';
import { applyTheme, storedPreference } from '@/shared/ui';

import '@fontsource/fira-sans/400.css';
import '@fontsource/fira-sans/500.css';
import '@fontsource/fira-sans/600.css';
import '@fontsource/fira-code/400.css';
import '@/shared/ui/tokens.css';
import '@/shared/ui/global.css';

/**
 * The entry point, and the four decisions that have to happen before React renders.
 *
 * The fonts are **self-hosted** rather than linked from a font CDN. The plan makes exactly this argument for the
 * locale catalogs - "they ship in the build, so text on a money-moving screen cannot change under a user between
 * deploys" - and it applies to the face those catalogs are set in. It is also the only third-party request a
 * financial application would otherwise make on every page load.
 *
 * The theme is applied **before** the first render, so the first paint is already the right colour scheme rather
 * than flashing white and then turning dark.
 */
applyTheme(storedPreference());

const container = document.getElementById('root');

if (container === null) {
  throw new Error('index.html has no #root element to mount into');
}

// One client, shared by the provider and the router's context. Two clients would be two caches: a screen would
// read what its loader fetched and never see it.
const queryClient = createQueryClient();

createRoot(container).render(
  <StrictMode>
    <AppProviders client={queryClient}>
      <RouterProvider router={createAppRouter(queryClient)} />
    </AppProviders>
  </StrictMode>,
);
