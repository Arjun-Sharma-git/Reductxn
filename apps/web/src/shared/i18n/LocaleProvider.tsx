import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react';

import { catalogFor, reviewStateOf, type Catalog } from './catalog';
import { DEFAULT_LOCALE, directionOf, renderable, type Direction } from './locales';
import { formatDateTime, formatNumber, translate, type Values } from './translate';

/**
 * The locale, for one screen tree.
 *
 * Props rather than a router or a store: `shared` may not know how a locale is chosen in a URL, and the thing
 * that does (`app/providers.tsx`) passes the answer down. That keeps this file testable by rendering it with a
 * locale and reading the output - no router, no history, no browser.
 *
 * What it *does* own is the two side effects the spec assigns to the locale: the document's `lang` and `dir`.
 * A screen reader picks a voice from `lang`, and `dir` is how the reserved RTL locales will work without
 * rewriting the layout (section 9). Both are set here rather than by every screen, so they cannot be forgotten
 * by a screen.
 */

export type Translations = {
  readonly locale: string;
  readonly direction: Direction;
  /** True when the requested locale is unreviewed on a screen that must not show one, and English is shown. */
  readonly fellBack: boolean;
  readonly t: (key: string, values?: Values) => string;
  readonly dateTime: (instant: string | Date, timeZone?: string) => string;
  readonly number: (value: number) => string;
};

const LocaleContext = createContext<Translations | null>(null);

export function LocaleProvider({
  locale,
  requireReviewed = false,
  children,
}: {
  /** The locale the URL (or a stored preference) resolved to, before the review check. */
  readonly locale: string;
  /**
   * Whether this screen moves money or asks for an approval, which is where an unreviewed locale may not be
   * shown (`docs/specs/i18n.md` section 8, point 4). It is a property of the *screen*: a settlement report and a
   * transfer approval are not the same risk as a settings page.
   */
  readonly requireReviewed?: boolean;
  readonly children: ReactNode;
}) {
  const resolved = renderable(locale, reviewStateOf(), { requireReviewed });
  const catalog: Catalog = useMemo(() => catalogFor(resolved.locale), [resolved.locale]);

  const direction = directionOf(resolved.locale);

  /**
   * A guard rather than a rule: the shell is the only locale provider (`routes/__root.tsx`), so nothing is ever
   * nested. It stays because the failure it prevents is silent - a second provider would write the document's
   * `lang` and, since React runs a child's effects before its parent's, the *outer* one would win. That is how the
   * first version of the review-state rule produced English text with `lang="ru"`.
   */
  const nested = useContext(LocaleContext) !== null;

  useEffect(() => {
    if (nested) {
      return;
    }

    document.documentElement.lang = resolved.locale;
    document.documentElement.dir = direction;
  }, [nested, resolved.locale, direction]);

  const value = useMemo<Translations>(
    () => ({
      locale: resolved.locale,
      direction,
      fellBack: resolved.fellBack,
      t: (key, values) => translate(resolved.locale, catalog, key, values),
      dateTime: (instant, timeZone) => formatDateTime(instant, resolved.locale, timeZone),
      number: (value) => formatNumber(value, resolved.locale),
    }),
    [resolved.locale, resolved.fellBack, direction, catalog],
  );

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useTranslations(): Translations {
  const value = useContext(LocaleContext);

  if (value === null) {
    throw new Error(
      'useTranslations was called outside a LocaleProvider. A component cannot render text without a locale, ' +
        'which is the point: the alternative is an English literal that ships to every locale (RULE F10).',
    );
  }

  return value;
}

export { DEFAULT_LOCALE };
