import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Settlement cycles: the instants a group has closed its books at.
 *
 * Two reads of one resource, because the contract serves two: the group's cycles (newest first, the page that shows
 * what happened after a member asked for a settlement) and the open operation, which answers with the same schema so
 * that a client never needs a second way to read a cycle.
 *
 * **The read exists so that asking for a settlement is visible.** A cycle has no status of its own - it is a claim
 * about an instant - so the only thing that says whether anything happened is `runId`: present means a plan exists
 * and can be read at `/runs/{runId}`, absent means the worker has not computed one (yet, or at all, because the group
 * had nothing recorded at or before that instant). `model/cycle.ts` is where that becomes a word a member reads.
 */
export type Cycle = paths['/groups/{groupId}/cycles']['get']['responses']['200']['content']['application/json'][number];

/** What opening one answers with: the same schema as a row of the list, so both readings agree. */
export type OpenedCycle = paths['/groups/{groupId}/cycles']['post']['responses']['201']['content']['application/json'];

export type OpenCycleBody = NonNullable<
  paths['/groups/{groupId}/cycles']['post']['requestBody']
>['content']['application/json'];

export const groupCyclesQuery = (groupId: string) =>
  queryOptions({
    queryKey: ['groups', groupId, 'cycles'] as const,
    queryFn: () => request<Cycle[]>('/groups/' + groupId + '/cycles'),
  });
