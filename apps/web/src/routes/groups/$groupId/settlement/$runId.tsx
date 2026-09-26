import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import { RunScreen } from '@/features/settlement';

/**
 * One run: what the plan asks for, and the answer.
 *
 * The run id is parsed here from the path and passed down as a string; the screen does not read the router, and this
 * route does not read a run (RULE F4 - parse, render, no decisions).
 */
export const Route = createFileRoute('/groups/$groupId/settlement/$runId')({
  validateSearch: z.object({ lang: z.string().optional() }),
  staticData: { requireReviewedLocale: true },
  component: RunRoute,
});

function RunRoute() {
  const { groupId, runId } = Route.useParams();

  return <RunScreen groupId={groupId} runId={runId} />;
}
