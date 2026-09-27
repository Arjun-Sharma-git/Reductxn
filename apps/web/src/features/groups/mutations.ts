import { useMutation, useQueryClient } from '@tanstack/react-query';

import type { Group } from '@/entities/group';
import { newIdempotencyKey, request } from '@/shared/api/client';

/**
 * Creating a group.
 *
 * **The idempotency key belongs to the attempt** (P8), and it is generated once per submit: a retry after a `503` repeats
 * the same key, which is exactly what makes the server answer with the group the first attempt created rather than making a
 * second one. `newIdempotencyKey` is `crypto.randomUUID()` for the reason the recording form's key is - two retries in the
 * same millisecond are two different attempts.
 *
 * **The answer is written into the cache rather than assumed.** The server is what drew the id, and it is what decided
 * whether this was a creation or a replay - so the list is invalidated and the screen reads what the record says. A group
 * added optimistically would be a group with an id the client invented.
 */
export function useCreateGroup() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (group: { name: string; currency: string }) =>
      request<Group>('/groups', {
        method: 'POST',
        body: group,
        idempotencyKey: newIdempotencyKey(),
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['groups'] });
    },
  });
}
