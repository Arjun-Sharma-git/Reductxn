import { createFileRoute } from '@tanstack/react-router';
import { z } from 'zod';

import { NettingPreviewScreen } from '@/features/netting';
import { LocaleProvider } from '@/shared/i18n';

/**
 * The preview screen's route: thin, as RULE F4 requires.
 *
 * It does three things: validates its search parameters, wraps the feature in a locale provider that demands a
 * *reviewed* translation, and renders one feature component. No data transformation, no arithmetic, no layout of
 * its own - the screen is the feature's, and this file is the URL's.
 *
 * **`requireReviewed` is why this route is more than plumbing.** `docs/specs/i18n.md` section 8 says the app must
 * refuse to render an unreviewed locale on money-moving and approval screens. A preview is where a member reads
 * "you will pay", so it is one of them: a machine-translated Russian sentence about money is not a cosmetic
 * defect, and the fallback to English - with a notice - is the honest answer until somebody reviews it.
 */
const search = z.object({
  lang: z.string().optional(),
  asOf: z.string().datetime({ offset: true }).optional(),
});

export const Route = createFileRoute('/groups/$groupId/netting')({
  validateSearch: search,
  component: NettingRoute,
});

function NettingRoute() {
  const { groupId } = Route.useParams();
  const { lang, asOf } = Route.useSearch();

  return (
    <LocaleProvider locale={lang ?? 'en'} requireReviewed>
      <NettingPreviewScreen groupId={groupId} asOf={asOf} />
    </LocaleProvider>
  );
}
