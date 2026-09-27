import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Invitations: the code that lets somebody join a group, what it is for, and the request that spends it.
 *
 * **It read nothing until ADR 0054, and that was the half of the flow that was missing.** One surface created a code and
 * another spent it, and spending answers with a member rather than with the invitation - so the person following a link
 * had to type their name into an operation that named no group, and a group with something to share had nothing to send
 * but an id. `invitePreviewQuery` is the read that closes it: the group a code joins, and whether the code still works.
 *
 * **The types live in an entity rather than in one feature** because two features need them and a feature may not import
 * another feature (RULE F1): the members screen creates a code, and the acceptance screen reads and spends one.
 */

/** A code that lets somebody join a group: the code, when it stops working, and its limit if it has one. */
export type Invite = paths['/groups/{groupId}/invites']['post']['responses']['201']['content']['application/json'];

/** What a code is for: the group it joins somebody to, and the code's own terms. */
export type InvitePreview = paths['/invites/{code}']['get']['responses']['200']['content']['application/json'];

/** Who is accepting: what the group will call them, and nobody else - the joiner is the caller (ADR 0042, 0047). */
export type AcceptInvite = NonNullable<
  paths['/invites/{code}/accept']['post']['requestBody']
>['content']['application/json'];

/**
 * What a code is for, asked without a session.
 *
 * **`retry: false`, and the same reason `groupsQuery` has it.** Every failure this read can answer is an answer rather
 * than a hiccup - a code nobody holds, one that ran out of time, one that ran out of uses - and asking again changes
 * none of them. The one that is not final is the network, and a person who has just followed a link can press the
 * browser's own reload.
 *
 * **The code is the query key, as it was typed.** Two spellings of one code are two keys, which is honest: the server
 * decides what a code means, and a client that canonicalised here would be a second place that knows the alphabet - the
 * same argument the route makes for passing the code through unchanged.
 */
export function invitePreviewQuery(code: string) {
  return queryOptions({
    queryKey: ['invites', code] as const,
    queryFn: () => request<InvitePreview>('/invites/' + code),
    retry: false,
  });
}
