import { createFileRoute } from '@tanstack/react-router';

import { MembersScreen } from '@/features/members';

/**
 * The members route: who is in the group, and recording somebody as a member.
 *
 * This file is the URL, and nothing else (RULE F4). There is no search parameter and no loader: the screen asks the
 * API for the group it was given.
 *
 * No `staticData.requireReviewedLocale`, and the omission is a decision rather than an oversight. The shell refuses
 * an unreviewed translation on money-moving and approval screens (docs/specs/i18n.md section 8.4): the obligations
 * route carries the flag because recording a commitment is a money-moving act, and the settlement screens carry it
 * because answering a plan is an approval. Recording a person is neither - it is a fact about the group's membership
 * - so an unreviewed locale falls back to English per key here rather than stopping the screen.
 */
export const Route = createFileRoute('/groups/$groupId/members')({
  component: MembersRoute,
});

function MembersRoute() {
  const { groupId } = Route.useParams();

  return <MembersScreen groupId={groupId} />;
}
