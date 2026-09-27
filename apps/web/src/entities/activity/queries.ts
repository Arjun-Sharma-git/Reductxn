import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * A group's history: who joined, who answered a run, and what was invited.
 *
 * **The feed is a page with a limit and no cursor**, which is the contract's own decision (ADR 0044): a cursor needs a total
 * order that stays stable as the union of three sources grows, and this build has not earned one. Fifty is the server's
 * default and this query asks for it, so a screen that grows will be the slice that adds pagination rather than one that
 * quietly renders three sources' worth of everything.
 *
 * **The response type is the contract's, so a kind this client does not know about is a compile-time problem** rather than a
 * row that renders as nothing: `kind` is an enum in the schema, and the screen below switches over all three.
 */
export type ActivityEvent =
  paths['/groups/{groupId}/activity']['get']['responses']['200']['content']['application/json'][number];

export function activityQuery(groupId: string) {
  return queryOptions({
    queryKey: ['groups', groupId, 'activity'] as const,
    queryFn: () => request<ActivityEvent[]>(`/groups/${groupId}/activity`),
    // A refusal here is the group read's refusal - not signed in, or not a member - and asking again does not change either.
    retry: false,
  });
}
