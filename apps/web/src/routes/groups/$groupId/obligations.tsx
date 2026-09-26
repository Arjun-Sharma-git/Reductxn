import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import type { ObligationFilters } from '@/entities/obligation';
import { ObligationsScreen } from '@/features/obligations';

/**
 * The obligations route: the list, its filters, and the form that records one.
 *
 * This file is the URL, and nothing else (RULE F4). The filters are parsed here from search parameters - so a
 * narrowed view is shareable, which is the reason the plan puts them in the URL rather than in component state -
 * and passed down as data. The feature never touches the router.
 *
 * `status` is the contract's enum rather than a free string: a typo in a bookmark becomes the unfiltered view
 * rather than a request the server refuses, and the enum is the same one the server validates against.
 *
 * `staticData.requireReviewedLocale` marks this as a money screen, because recording a commitment is a
 * money-moving act (docs/specs/i18n.md section 8.4) - the shell reads it and refuses an unreviewed translation.
 */
const search = z.object({
  lang: z.string().optional(),
  status: z.enum(['open', 'netted', 'settled', 'voided']).optional(),
  memberId: z.string().uuid().optional(),
  from: z.string().optional(),
  to: z.string().optional(),
  // The two orders the API accepts, rather than a free string: the server refuses a sort it does not order by, and
  // a typo in a bookmark should be the unfiltered view rather than a refused request - the same reasoning the status
  // enum above gets. `-occurredAt` is the default, so an absent value is not a missing parameter.
  sort: z.enum(['occurredAt', '-occurredAt']).optional(),
});

export const Route = createFileRoute('/groups/$groupId/obligations')({
  validateSearch: search,
  staticData: { requireReviewedLocale: true },
  component: ObligationsRoute,
});

function ObligationsRoute() {
  const { groupId } = Route.useParams();
  const searchParams = Route.useSearch();
  const navigate = Route.useNavigate();

  const filters: ObligationFilters = {
    ...(searchParams.status === undefined ? {} : { status: searchParams.status }),
    ...(searchParams.memberId === undefined ? {} : { memberId: searchParams.memberId }),
    ...(searchParams.from === undefined ? {} : { from: searchParams.from }),
    ...(searchParams.to === undefined ? {} : { to: searchParams.to }),
    ...(searchParams.sort === undefined ? {} : { sort: searchParams.sort }),
  };

  return (
    <ObligationsScreen
      groupId={groupId}
      filters={filters}
      onFiltersChange={(next) => {
        // A changed filter is a new question, so it replaces the old one rather than accumulating: `undefined`
        // removes the parameter, which is how "any status" is expressed.
        void navigate({ search: (previous) => ({ ...previous, ...next }) });
      }}
    />
  );
}
