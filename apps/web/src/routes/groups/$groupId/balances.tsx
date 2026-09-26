import { createFileRoute } from '@tanstack/react-router';

import { BalancesScreen } from '@/features/balances';

/**
 * The balances route: what each member owes or is owed right now.
 *
 * This file is the URL, and nothing else (RULE F4). There is no search parameter and no loader: the screen asks the
 * API for the group it was given.
 *
 * No `staticData.requireReviewedLocale`, and the omission is a decision. The rule (`docs/specs/i18n.md` section 8.4)
 * refuses an unreviewed translation where a member is told what they will pay or where a plan is approved. This
 * screen is a *read* of positions that already exist - it moves no money and asks for no answer - so an unreviewed
 * locale falls back to English per key, exactly as the members and obligations screens do, and the figures themselves
 * are unaffected either way.
 */
export const Route = createFileRoute('/groups/$groupId/balances')({
  component: BalancesRoute,
});

function BalancesRoute() {
  const { groupId } = Route.useParams();

  return <BalancesScreen groupId={groupId} />;
}
