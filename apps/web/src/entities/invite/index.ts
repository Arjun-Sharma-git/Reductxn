import type { paths } from '@/shared/api/client';

/**
 * Invitations: the code that lets somebody join a group, and the request that spends it.
 *
 * **Types only, and that is deliberate rather than unfinished.** Neither surface reads an invitation: one *creates* a
 * code and the other *spends* it, and spending answers with a member rather than with the invitation (the contract says
 * so, and the reason is that after joining, the invitation is done - what a client needs next is who the group now
 * has). Adding a query here for "the invitation I just made" would be a read nobody asks for, and the contract has no
 * operation behind it.
 *
 * The types live in an entity rather than in one feature because two features need them and a feature may not import
 * another feature (RULE F1): the members screen creates a code, and the acceptance screen spends one.
 */

/** A code that lets somebody join a group: the code, when it stops working, and its limit if it has one. */
export type Invite = paths['/groups/{groupId}/invites']['post']['responses']['201']['content']['application/json'];

/** Who is accepting: what the group will call them, and nobody else - the joiner is the caller (ADR 0042, 0047). */
export type AcceptInvite = NonNullable<
  paths['/invites/{code}/accept']['post']['requestBody']
>['content']['application/json'];
