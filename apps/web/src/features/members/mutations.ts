import { useMutation, useQueryClient } from '@tanstack/react-query';

import { membersQuery, type Member, type NewMember } from '@/entities/member';
import { request } from '@/shared/api/client';

/**
 * Recording a member of the group.
 *
 * **No idempotency key: the pair is the key.** `(groupId, userId)` is the table's primary key, so a retry whose
 * response was lost collides with the row the first attempt wrote, and a request for somebody who is already a member
 * answers `200` with the member as they stand (P8, ADR 0035). A key would add nothing a constraint does not already
 * guarantee.
 *
 * **Nothing is written optimistically, and the reason is the difference between a membership and its effect.** Adding
 * a member changes what *every* screen that names a person can offer - the obligations form's two selects, the
 * filters, a run's answers - so a row invented in the cache would be one screen claiming a name the server had not
 * recorded. What the member gets instead is the server's own answer, and the re-read that shows the row.
 *
 * The invalidation is the whole members query rather than one entry, because the query key is the group: every screen
 * that names anybody reads the same list.
 */
export function useAddMember(groupId: string) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (variables: NewMember) =>
      request<Member>('/groups/' + groupId + '/members', { method: 'POST', body: variables }),

    onSuccess: () => {
      void client.invalidateQueries({ queryKey: membersQuery(groupId).queryKey });
    },
  });
}
