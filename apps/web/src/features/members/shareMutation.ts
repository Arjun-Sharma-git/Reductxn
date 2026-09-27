import { useMutation } from '@tanstack/react-query';

import type { Invite } from '@/entities/invite';
import { request } from '@/shared/api/client';

/**
 * A link that shows what the group owes whom, without joining anybody (ADR 0055).
 *
 * **The same shape as {@link useCreateInvite} and for the same reasons**: nothing is written optimistically (the value
 * of the answer is the code the server generated, and a code invented in the cache would be one nobody can open), and
 * nothing is invalidated (no read of a group's codes exists, and the group's own numbers have not changed - a link is
 * something handed out, not something recorded in the ledger).
 */
export function useCreateShare(groupId: string) {
  return useMutation({
    mutationFn: () => request<Invite>('/groups/' + groupId + '/shares', { method: 'POST' }),
  });
}