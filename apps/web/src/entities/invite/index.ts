import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Invitations: the code that lets somebody join a group, what a code is for, the link that lets somebody look, and the
 * requests that make and spend them.
 *
 * **It read nothing until ADR 0054, and that was the half of the flow that was missing.** One surface created a code and
 * another spent it, and spending answers with a member rather than with the invitation - so the person following a link
 * had to type their name into an operation that named no group, and a group with something to share had nothing to send
 * but an id. `invitePreviewQuery` is the read that closed it: the group a code is for, and whether the code still works.
 *
 * **ADR 0055 added the second kind of code and the read that uses one.** `sharedViewQuery` is what a share link shows -
 * the group's positions, with the names the group recorded - and it is asked with no session at all, which is the
 * operation's shape rather than an omission: the person who follows a link an accountant was sent has no account here.
 *
 * **The types live in an entity rather than in one feature** because several features need them and a feature may not
 * import another feature (RULE F1): the members screen creates both kinds of code, and the invitation screen reads one
 * while the balances screen reads the other.
 */

/** A code a group hands out: the code, what it grants, when it stops working, and its limit if it has one. */
export type Invite = paths['/groups/{groupId}/invites']['post']['responses']['201']['content']['application/json'];

/** What a code is for: the group it is for, what holding it entitles somebody to, and the code's own terms. */
export type InvitePreview = paths['/invites/{code}']['get']['responses']['200']['content']['application/json'];

/** What a share link shows: the group, what it owes whom right now, and when the link stops working. */
export type SharedView = paths['/shared/{code}']['get']['responses']['200']['content']['application/json'];

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

/**
 * What a share link shows, asked without a session and never retried.
 *
 * The same shape and the same reasons as {@link invitePreviewQuery}, and one more that is about this read's audience: a
 * refusal here is often ordinary rather than exceptional - a link an accountant bookmarked a fortnight ago has expired,
 * and that is a sentence to render rather than an error to retry.
 */
export function sharedViewQuery(code: string) {
  return queryOptions({
    queryKey: ['shared', code] as const,
    queryFn: () => request<SharedView>('/shared/' + code),
    retry: false,
  });
}
