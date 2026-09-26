import { useMutation } from '@tanstack/react-query';

import type { AcceptInvite } from '@/entities/invite';
import type { Member } from '@/entities/member';
import { request } from '@/shared/api/client';

/**
 * Spending an invitation code.
 *
 * **It answers with a member, because that is what joining produces.** The contract says so, and it is what makes the
 * screen able to say who the group now has rather than only that something worked.
 *
 * **Nothing is invalidated.** Accepting changes the group's member list, and this client cannot say which: the response
 * is the member, not the group, and the code does not have to name it. That is a real consequence of the contract's
 * shape - acceptance is addressed by the code alone - and it is a consequence worth accepting rather than working
 * around, because the person accepting is almost never looking at the group's member list at that moment. A member of
 * the group who accepts a code *for their own group* gets the same answer and no stale list, because nothing changed.
 */
export function useAcceptInvite(code: string) {
  return useMutation({
    mutationFn: (joining: AcceptInvite) =>
      request<Member>('/invites/' + code + '/accept', { method: 'POST', body: joining }),
  });
}
