import { useMutation, useQueryClient } from '@tanstack/react-query';

import type { CycleSchedule, NewSchedule } from '@/entities/schedule';
import { request } from '@/shared/api/client';

/**
 * Stating a group's cadence, and stopping one (ADR 0057).
 *
 * **Both invalidate the read, and here that is not a formality.** The schedule's `nextDueAt` is advanced by the worker
 * between one read and the next, so the value this screen showed a moment ago may already be a period out of date - and
 * a mutation that did not re-read would leave the screen asserting a due instant the worker has moved past. The server's
 * answer to the write is used for the immediate display; the invalidation is what keeps it true afterwards.
 */
export function useSetSchedule(groupId: string) {
  const cache = useQueryClient();

  return useMutation({
    mutationFn: (schedule: NewSchedule) =>
      request<CycleSchedule>('/groups/' + groupId + '/schedule', { method: 'PUT', body: schedule }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ['groups', groupId, 'schedule'] }),
  });
}

/** Stops the cadence. The read is invalidated for the same reason the write above is: absence is the new answer. */
export function useClearSchedule(groupId: string) {
  const cache = useQueryClient();

  return useMutation({
    mutationFn: () => request<void>('/groups/' + groupId + '/schedule', { method: 'DELETE' }),
    onSuccess: () => cache.invalidateQueries({ queryKey: ['groups', groupId, 'schedule'] }),
  });
}