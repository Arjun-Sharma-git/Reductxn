import { createRootRoute, Link, Outlet, useMatches, useSearch } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';

import { currentUserQuery } from '@/entities/identity';
import { useSignOut } from '@/features/sign-in';
import { availableLocales, bestMatch, DEFAULT_LOCALE, LocaleProvider, useTranslations } from '@/shared/i18n';
import { env } from '@/shared/config/env';
import { AppHeaderControls } from '@/shared/ui';

/**
 * The shell: what every screen is inside.
 *
 * It owns three things and no fourth. The locale, resolved from the URL and provided to the tree - which is why
 * the search-param schema for `lang` is here rather than repeated on every route. The skip link, because the first
 * tab stop of a report should not be the header. And the header itself, with the two document-level preferences.
 *
 * **It is the only locale provider, and that is a decision with a bug behind it.** A money screen needs an
 * unreviewed translation refused (`docs/specs/i18n.md` section 8.4), and the first version of this gave each such
 * screen its own provider around itself. That cannot work: both providers write the document's `lang`, React runs a
 * child's effects before its parent's, so the *outer* one won - an English screen announced as Russian to a screen
 * reader, which the browser test caught. A parent cannot know what a child decided, so the parent has to be *told*:
 * a route declares `staticData.requireReviewedLocale`, the shell reads the matches, and there is one provider, one
 * decision and one attribute.
 *
 * It deliberately does **not** resolve a locale from a stored user preference: that is step one of the spec's order
 * and it needs identity, which does not exist yet. The URL is step two and the browser's `Accept-Language` is step
 * three, both of which work today.
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

  // The current route's own declaration, which is the only place that knows whether the screen it renders shows
  // money. A route that says nothing requires nothing.
  const requireReviewed = useMatches({
    select: (matches) =>
      matches.some(
        (match) => (match.staticData as { requireReviewedLocale?: boolean }).requireReviewedLocale === true,
      ),
  });

  return (
    <LocaleProvider locale={locale} requireReviewed={requireReviewed}>
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
        <Account />
        <AppHeaderControls availableLocales={availableLocales()} />
      </header>

      <main id="content" className="rn-app-main">
        <Outlet />
      </main>
    </>
  );
}

/**
 * Who is signed in, in the header - or the way to become somebody.
 *
 * **A failed read is the signed-out state rather than an error to show.** `GET /me` answers `401` to a visitor, so this
 * treats a refusal as "no name to show" and offers a sign-in link: a header that rendered a problem notice at every
 * anonymous visitor would be shouting at the ordinary case.
 *
 * **It is here rather than in a feature's screen** because it belongs to the shell - it is true of every screen at once.
 * The read comes from `entities/identity` and the sign-out from `features/sign-in`, which is the direction imports are
 * allowed to flow (RULE F1): a route may reach both.
 */
function Account() {
  const { t } = useTranslations();

  const me = useQuery(currentUserQuery());
  const signOut = useSignOut();

  if (me.isSuccess) {
    return (
      <span className="rn-app-account">
        <span>{t('common.signedInAs', { name: me.data.displayName })}</span>
        <button type="button" className="rn-app-account-action" onClick={signOut}>
          {t('common.signOut')}
        </button>
      </span>
    );
  }

  return (
    <Link to="/sign-in" className="rn-app-account">
      {t('common.signIn')}
    </Link>
  );
}
