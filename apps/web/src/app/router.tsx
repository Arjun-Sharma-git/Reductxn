import { createRouter } from '@tanstack/react-router';
import type { QueryClient } from '@tanstack/react-query';

import { routeTree } from '@/routeTree.gen';

/**
 * The router, in SPA mode (ADR 0007: Router runs in SPA mode against the Spring Boot backend).
 *
 * The route tree is generated from `src/routes` - the filesystem *is* the routing table, so a screen cannot exist
 * without a route and a route cannot exist without a file. `routeTree.gen.ts` is committed (RULE C2) so a clone
 * builds without running the generator first.
 *
 * The query client is in the router's context so that a loader can `ensureQueryData` - which is how a screen
 * avoids rendering a spinner it does not need. Slice one's screens fetch in the component because there is
 * nothing to preload yet; the seam is here so that adding a loader is not a refactor of the router.
 */
export function createAppRouter(queryClient: QueryClient) {
  return createRouter({
    routeTree,
    context: { queryClient },
    defaultPreload: 'intent',
    scrollRestoration: true,
  });
}

/**
 * The router's type, registered once.
 *
 * Without this, every `Link to=...` is a string and every `useParams()` is a guess. With it, a mistyped path or a
 * parameter a route does not have is a compile error - the same argument the generated API types make, applied to
 * navigation.
 */
declare module '@tanstack/react-router' {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
