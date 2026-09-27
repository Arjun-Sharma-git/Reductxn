import { createFileRoute } from '@tanstack/react-router';

import { ScheduleScreen } from '@/features/schedule';

/**
 * The group's schedule, at {@code /groups/$groupId/schedule}.
 *
 * <p>This file is the URL, and nothing else (RULE F4). It is inside the group's tree because the operation is: the
 * contract addresses a schedule by the group, unlike a share link or an invitation, which carry their group in a code
 * (ADRs 0041, 0055).
 *
 * <p>No {@code staticData.requireReviewedLocale}: the rule refuses an unreviewed translation where a member is told what
 * they will pay or where a plan is approved (`docs/specs/i18n.md` section 8.4). This screen states when a period closes,
 * which is a fact about the group's own books rather than a payment instruction.
 */
export const Route = createFileRoute('/groups/$groupId/schedule')({
  component: ScheduleRoute,
});

function ScheduleRoute() {
  const { groupId } = Route.useParams();

  return <ScheduleScreen groupId={groupId} />;
}