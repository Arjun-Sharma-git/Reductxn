import { useMutation, useQueryClient } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';
import { forgetSession, sessionFrom, storeSession } from '@/shared/session';

/**
 * The two operations of sign-in, and the one that ends it.
 *
 * **The pair goes straight into `shared/session` and the whole cache is dropped on the way in.** A session changes what
 * every query *means* rather than only what it returns: a member catalogue is one thing to a signed-out visitor and
 * another to a signed-in person, and screens that briefly showed the old answer would be showing somebody else's group.
 * `clear()` is the blunt version of that, and it is right here - signing in and signing out are the only two events where
 * every cached answer is in doubt.
 *
 * **Nothing is written optimistically**, for the reason the invite card gives: the value is a token the *server* drew, and
 * a token invented in the cache is a token that does not work.
 */

type TokenPair = paths['/auth/otp/verify']['post']['responses']['200']['content']['application/json'];

/** Ask for a code. The response is empty on purpose - the code goes to the phone, never into this client. */
export function useRequestCode() {
  return useMutation({
    mutationFn: (phoneNumber: string) =>
      request<void>('/auth/otp/request', { method: 'POST', body: { phoneNumber } }),
  });
}

/** Hand the code back and keep the session. */
export function useVerifyCode() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (code: { phoneNumber: string; code: string; deviceId: string }) =>
      request<TokenPair>('/auth/otp/verify', { method: 'POST', body: code }),
    onSuccess: (pair) => {
      storeSession(sessionFrom(pair));

      // **Invalidate everything, and not `clear()`.** Both say "every cached answer is in doubt", and `clear()` says it
      // more loudly - it removes the queries. What it does not do is tell the *mounted* ones to fetch again, so the header
      // kept offering a sign-in link after a successful sign-in while the shell's `GET /me` sat there removed and
      // unanswered. The browser test caught it. Invalidating marks the whole cache stale and refetches what is on screen,
      // which is the behaviour the sentence was meant to buy.
      void client.invalidateQueries();
    },
  });
}

/**
 * Sign out.
 *
 * It forgets the pair locally and invalidates the cache, and it does **not** call the server. There is no operation that
 * ends a session: the token stays valid until its window closes, which is why the access half is thirty minutes and why
 * this is recorded as a gap rather than pretended away (ADR 0042). A `DELETE /auth/session` would be the honest way to do
 * it, and adding one is a contract change somebody has to decide on.
 */
export function useSignOut() {
  const client = useQueryClient();

  return () => {
    forgetSession();
    void client.invalidateQueries();
  };
}
