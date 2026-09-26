import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * `GET /me`: who is asking.
 *
 * **The read that lets every other screen stop guessing.** Before it, the client had no way to know which member a person
 * is, so the member catalogue's fallback - show the uuid - was the only thing screens could do when a name was missing
 * (ADR 0035, 0038). What this adds is the other end of that: the person looking at the screen.
 *
 * **A failure is expected, not exceptional.** A signed-out visitor gets a `401`, and the shell has to render something
 * useful for them - a sign-in link - so this query does not retry and does not treat the refusal as an error to shout
 * about (`ProblemNotice` is not used for it anywhere). One refusal is worth calling out: `api.not_signed_in` is also what
 * an *expired* session answers, and the request layer renews once before that reaches here, so seeing it means the session
 * really is over.
 */
export type CurrentUser = paths['/me']['get']['responses']['200']['content']['application/json'];

export function currentUserQuery() {
  return queryOptions({
    queryKey: ['me'] as const,
    queryFn: () => request<CurrentUser>('/me'),
    // Nothing to retry: the only failure this can have is "not signed in", and asking again does not change that.
    retry: false,
  });
}
