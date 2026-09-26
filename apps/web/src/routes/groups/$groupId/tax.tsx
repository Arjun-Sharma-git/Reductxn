import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import { TaxScreen } from '@/features/tax';

/**
 * The tax route: what the group is charged under, and what its plans have assessed.
 *
 * This file is the URL, and nothing else (RULE F4). The period is parsed here from search parameters, so a range a
 * reader asked for is shareable - which is the reason the plan puts filters in the URL rather than in component state,
 * and the same decision the obligations route makes for its own filters.
 *
 * The two dates are checked against the shape the API reads (`YYYY-MM-DD`) rather than accepted as any string: a typo
 * in a bookmark becomes the unfiltered report instead of a request the server refuses.
 *
 * No `staticData.requireReviewedLocale`, and the omission is a decision. The rule (`docs/specs/i18n.md` section 8.4)
 * refuses an unreviewed translation where a member is told what they will pay or where a plan is approved. This screen
 * reports figures that already exist - it moves no money and asks for no answer - so an unreviewed locale falls back to
 * English per key, as the members, obligations and balances screens do.
 */
const search = z.object({
  lang: z.string().optional(),
  periodStart: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/)
    .optional(),
  periodEnd: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}$/)
    .optional(),
});

export const Route = createFileRoute('/groups/$groupId/tax')({
  validateSearch: search,
  component: TaxRoute,
});

function TaxRoute() {
  const { groupId } = Route.useParams();
  const searchParams = Route.useSearch();
  const navigate = Route.useNavigate();

  return (
    <TaxScreen
      groupId={groupId}
      period={{ start: searchParams.periodStart, end: searchParams.periodEnd }}
      onPeriodChange={(next) => {
        // A changed period is a new question, so it replaces the old one rather than accumulating: `undefined`
        // removes the parameter, which is how "everything" is expressed.
        void navigate({ search: (previous) => ({ ...previous, ...next }) });
      }}
    />
  );
}
