import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * The groups a person belongs to, and one group on its own.
 *
 * **Two queries because the two reads answer different questions.** `groupsQuery` is "the groups I am in", which only makes
 * sense with a session - a signed-out visitor gets a `401`, and the home screen treats that as "here is how to sign in"
 * rather than as an error to show. `groupQuery` is one group's own screen, which a group's layout asks for to put a name
 * above the nav.
 *
 * **`groupQuery` fails silently and deliberately.** A group id with commitments but no group row is a state this build
 * really is in - `groups` arrived as an additive table (ADR 0043) - so a layout that showed a problem notice for a `404`
 * would break screens that work today. The layout falls back to the id instead, which is what it showed before this slice.
 */
export type Group = paths['/groups']['get']['responses']['200']['content']['application/json'][number];

export function groupsQuery() {
  return queryOptions({
    queryKey: ['groups'] as const,
    queryFn: () => request<Group[]>('/groups'),
    // Nothing to retry: the only failure this can have is "not signed in", and asking again does not change that.
    retry: false,
  });
}

export function groupQuery(groupId: string) {
  return queryOptions({
    queryKey: ['groups', groupId] as const,
    queryFn: () => request<Group>(`/groups/${groupId}`),
    retry: false,
  });
}
