import { createFileRoute } from '@tanstack/react-router';

import { ActivityScreen } from '@/features/activity';

/**
 * A group's history.
 *
 * A thin route (RULE F4): the group id is the path's, and everything else is the feature's. No `requireReviewedLocale` - the
 * feed is a list of things that happened, not a statement about what a member will pay (ADR 0028, `docs/specs/i18n.md`
 * section 8.4).
 */
export const Route = createFileRoute('/groups/$groupId/activity')({
  component: ActivityRoute,
});

function ActivityRoute() {
  const { groupId } = Route.useParams();

  return <ActivityScreen groupId={groupId} />;
}
