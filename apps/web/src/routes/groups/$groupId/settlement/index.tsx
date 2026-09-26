import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import { RunsScreen } from '@/features/settlement';

/**
 * The group's runs: the page a member opens to find out what needs a decision.
 *
 * Thin, like every route here (RULE F4): the search schema, the group id, the screen. `lang` is what this route
 * carries, because the language is part of the page's identity rather than a component's state.
 *
 * `staticData.requireReviewedLocale` marks it as a money screen - approving a plan is the most money-moving act in
 * the product - so an unreviewed translation is refused here (docs/specs/i18n.md section 8.4).
 */
export const Route = createFileRoute('/groups/$groupId/settlement/')({
  validateSearch: z.object({ lang: z.string().optional() }),
  staticData: { requireReviewedLocale: true },
  component: RunsRoute,
});

function RunsRoute() {
  const { groupId } = Route.useParams();

  return <RunsScreen groupId={groupId} />;
}
