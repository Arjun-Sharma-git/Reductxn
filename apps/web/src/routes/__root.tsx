import { createRootRoute, Link, Outlet, useSearch } from '@tanstack/react-router';
import { z } from 'zod';

import { availableLocales, bestMatch, DEFAULT_LOCALE, LocaleProvider, useTranslations } from '@/shared/i18n';
import { env } from '@/shared/config/env';
import { AppHeaderControls } from '@/shared/ui';

/**
 * The shell: what every screen is inside.
 *
 * It owns three things and no fourth. The locale, resolved from the URL and provided to the tree - which is why
 * the search-param schema for `lang` is here rather than repeated on every route. The skip link, because the
 * first tab stop of a report should not be the header. And the header itself, with the two document-level
 * preferences.
 *
 * It deliberately does **not** resolve a locale from a stored user preference: that is step one of the spec's
 * order and it needs identity, which does not exist yet (`docs/specs/i18n.md` section 3). The URL is step two
 * and the browser's `Accept-Language` is step three, both of which work today.
 */
const search = z.object({
  /** The locale, part of the page's identity: a link shared in Russian opens in Russian. */
  lang: z.string().optional(),
});

export const Route = createRootRoute({
  validateSearch: search,
  component: RootLayout,
});

function RootLayout() {
  const { lang } = useSearch({ from: Route.id });
  const locale = lang ?? bestMatchFromBrowser();

  return (
    <LocaleProvider locale={locale}>
      <Shell />
    </LocaleProvider>
  );
}

/**
 * The browser's preference, matched once.
 *
 * `navigator.languages` is the full list in the user's order of preference, so the first supported entry wins -
 * which is why this reads the list rather than `navigator.language`. The matching itself is `shared/i18n`'s
 * (`bestMatch`), because a second copy of "is this tag supported" is a second answer to that question.
 */
function bestMatchFromBrowser(): string {
  const supported = availableLocales();

  if (supported.length === 0) {
    return DEFAULT_LOCALE;
  }

  return bestMatch(globalThis.navigator?.languages ?? [], supported);
}

function Shell() {
  const { t } = useTranslations();

  return (
    <>
      <a className="rn-skip-link" href="#content">
        {t('common.skipToContent')}
      </a>

      <header className="rn-app-header">
        <Link to="/" className="rn-app-brand">
          {env.appName}
        </Link>
        <AppHeaderControls availableLocales={availableLocales()} />
      </header>

      <main id="content" className="rn-app-main">
        <Outlet />
      </main>
    </>
  );
}
