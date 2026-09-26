import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

/**
 * The composition root's providers.
 *
 * One query client, created once for the app's lifetime, and configured rather than left at its defaults for two
 * reasons worth writing down:
 *
 *   - `retry` is a function rather than three attempts for everything. A 4xx is an answer - retrying a request
 *     the server has already refused is load, not resilience - while a network failure or a 503 is worth one more
 *     try, with the exponential backoff the library already implements.
 *   - `refetchOnWindowFocus` is on, and that is deliberate for a settlement screen: a member who leaves the tab
 *     open and comes back should see the plan as it is now rather than as it was an hour ago. It costs a request
 *     that the server answers from a table.
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: (attempt, error) => {
          const status = (error as { problem?: { status?: number } }).problem?.status ?? 0;

          if (status >= 400 && status < 500) {
            return false;
          }

          return attempt < 2;
        },
        staleTime: 30_000,
        refetchOnWindowFocus: true,
      },
      mutations: {
        retry: false,
      },
    },
  });
}

export function AppProviders({
  client,
  children,
}: {
  readonly client: QueryClient;
  readonly children: ReactNode;
}) {
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
