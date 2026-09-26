import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * A group's members: who can be a debtor, a creditor, or a filter.
 *
 * The contract's `Member` carries a `displayName`, which is the whole reason this query exists - a form that asks
 * a person to type two UUIDs is a form that will be filled in wrongly, and an obligations list that shows two
 * UUIDs instead of two names is a list nobody can read.
 */
export type Member = paths['/groups/{groupId}/members']['get']['responses']['200']['content']['application/json'][number];

export const membersQuery = (groupId: string) =>
  queryOptions({
    queryKey: ['groups', groupId, 'members'] as const,
    queryFn: () => request<Member[]>(`/groups/${groupId}/members`),
    // Membership changes rarely and is read on every screen that names anybody.
    staleTime: 5 * 60 * 1000,
  });

/**
 * A member's name, or their identifier.
 *
 * The fallback is deliberate rather than defensive: an obligation can name somebody the catalogue of members does
 * not contain - a member who has left, or a list that failed to load - and showing the identifier is honest about
 * that, where showing "Unknown member" would be the screen inventing a fact about a person.
 */
export function nameOf(members: readonly Member[], userId: string): string {
  return members.find((member) => member.userId === userId)?.displayName ?? userId;
}
