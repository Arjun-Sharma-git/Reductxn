import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * The netting preview: what the group's open obligations would come to if they were settled now.
 *
 * A read-only what-if, which is why it is the first screen this client has. It needs no identity, no write path
 * and no optimistic update, and it carries the two things this product exists to show: what each member's
 * position is, and what netting saves against settling every commitment pair by pair.
 *
 * `asOf` is part of the query key because it is part of the *question* - a preview for a past instant is a
 * different answer, and a cache that ignored it would show yesterday's plan under today's heading. The contract
 * makes the same argument for `asOf` on the server side.
 */
export type NettingPreview =
  paths['/groups/{groupId}/netting/preview']['get']['responses']['200']['content']['application/json'];

export type Partition = NettingPreview['partitions'][number];

export const nettingPreviewQuery = (groupId: string, asOf?: string) =>
  queryOptions({
    queryKey: ['netting', 'preview', groupId, asOf ?? 'now'] as const,
    queryFn: () =>
      request<NettingPreview>(`/groups/${groupId}/netting/preview`, {
        query: { asOf },
      }),
  });

/**
 * The group's balances: the same positions, as they stand now (ADR 0039).
 *
 * It lives beside the preview's query rather than in an entity of its own, because it is the same fact - a member's
 * net position - asked without the what-if. The contract tags both operations `netting` for the same reason, and the
 * server computes them with the same planner, which is what stops the two screens disagreeing.
 *
 * No `asOf`, unlike the preview: the contract gives this read none, and the server's answer is "now". A caller who
 * wants a past instant is asking the preview's question, and it is already there.
 */
export type Balances =
  paths['/groups/{groupId}/balances']['get']['responses']['200']['content']['application/json'];

export const groupBalancesQuery = (groupId: string) =>
  queryOptions({
    queryKey: ['groups', groupId, 'balances'] as const,
    queryFn: () => request<Balances>(`/groups/${groupId}/balances`),
  });
