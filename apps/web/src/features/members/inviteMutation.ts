import { useMutation } from '@tanstack/react-query';

import type { Invite } from '@/entities/invite';
import { request } from '@/shared/api/client';

/**
 * Making an invitation code.
 *
 * **Nothing is written optimistically, and here that matters more than usual.** The whole value of the answer is the
 * code the *server* generated - it is the primary key, the store retries collisions to make it unique, and a code
 * invented in the cache would be a code nobody can spend. So the card waits for the answer and shows what came back.
 *
 * **Nothing is invalidated either.** Creating an invitation changes no list the client holds: there is no read of a
 * group's invitations (the contract has none), and the members list is unaffected until somebody accepts. Invalidating
 * would be a re-read of something that did not change.
 */
export function useCreateInvite(groupId: string) {
  return useMutation({
    mutationFn: () => request<Invite>('/groups/' + groupId + '/invites', { method: 'POST' }),
  });
}
