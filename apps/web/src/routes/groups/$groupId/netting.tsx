import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import { NettingPreviewScreen } from '@/features/netting';

/**
 * The preview screen's route: thin, as RULE F4 requires.
 *
 * It validates its search parameters, renders one feature component, and **declares that it needs a reviewed
 * translation**. That declaration is `staticData` rather than a provider wrapped around the screen, and the
 * difference is not cosmetic: the document's `lang` and `dir` are set once, by the shell, and a screen that
 * wrapped itself in a second provider would be fighting it over the same attribute. React runs a child's effects
 * before its parent's, so "the inner one wins" is not something a parent can arrange by inspection - the shell can
 * only know by being told, and the route is where the risk of the screen is known.
 *
 * **Why this screen requires review.** `docs/specs/i18n.md` section 8 says an unreviewed locale may not be shown
 * on money-moving and approval screens. A preview is where a member reads "you will pay", so a machine-translated
 * Russian sentence about money is not a cosmetic defect - and the fallback to English, with a notice, is the
 * honest answer until somebody reviews the translation.
 */
const search = z.object({
  lang: z.string().optional(),
  asOf: z.string().datetime({ offset: true }).optional(),
});

export const Route = createFileRoute('/groups/$groupId/netting')({
  validateSearch: search,
  staticData: { requireReviewedLocale: true },
  component: NettingRoute,
});

function NettingRoute() {
  const { groupId } = Route.useParams();
  const { asOf } = Route.useSearch();

  return <NettingPreviewScreen groupId={groupId} asOf={asOf} />;
}
