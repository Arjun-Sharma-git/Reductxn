import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Settlement runs: what a group has to decide about, and what one plan actually says.
 *
 * Three reads rather than one, because the contract serves three: the group's runs (newest first, the page a member
 * chooses from), one run (its figures, its gates and who has answered), and its transfers (the who-pays-whom list,
 * which the read endpoint deliberately leaves to the endpoint that exists for it).
 *
 * The status is a `string`, not a union, and that is the read model's own decision rather than an oversight: the
 * vocabulary is fixed by the worker's `CHECK` constraint, and a client that enumerated the states it had heard of
 * would break on a worker that learned a ninth. `model/decision.ts` is where an unknown word becomes a presentable
 * phrase rather than a raw identifier.
 */
export type Run = paths['/runs/{runId}']['get']['responses']['200']['content']['application/json'];

/** One row of the group's page: the same shape, without the transfers the other endpoint serves. */
export type RunRow = paths['/groups/{groupId}/runs']['get']['responses']['200']['content']['application/json'][number];

export type Transfer = paths['/runs/{runId}/transfers']['get']['responses']['200']['content']['application/json'][number];

export type RecordedDecision = paths['/runs/{runId}/decisions']['post']['responses']['200']['content']['application/json'];

export const groupRunsQuery = (groupId: string) =>
  queryOptions({
    queryKey: ['groups', groupId, 'runs'] as const,
    queryFn: () => request<RunRow[]>('/groups/' + groupId + '/runs'),
  });

export const runQuery = (runId: string) =>
  queryOptions({
    queryKey: ['runs', runId] as const,
    queryFn: () => request<Run>(`/runs/${runId}`),
  });

export const runTransfersQuery = (runId: string) =>
  queryOptions({
    queryKey: ['runs', runId, 'transfers'] as const,
    queryFn: () => request<Transfer[]>(`/runs/${runId}/transfers`),
  });
