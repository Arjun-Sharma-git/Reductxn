/**
 * The launch locales, and how one is chosen.
 *
 * `docs/specs/i18n.md` section 1 fixes the set and the reasons: English is the source of truth, Hindi and
 * Japanese are the plural-extreme cases (two forms and one), Russian is the four-form case that makes ICU
 * non-optional, and Arabic and Hebrew are reserved - not launched, but their direction is already in the layout
 * because retrofitting RTL is a rewrite rather than a token change.
 *
 * Resolution follows the spec's order (section 3): a signed-in user's preference, then the URL, then
 * `Accept-Language`, then English. The first of those needs identity, which does not exist yet, so it is the one
 * step of the four that is not implemented - and it is a step *before* the URL rather than a missing branch, so
 * adding it later changes the order of two arguments rather than the shape of the code.
 */

export type Direction = 'ltr' | 'rtl';

export type LocaleDefinition = {
  readonly code: string;
  /** The locale's name in itself, which is what a language switcher shows: a user looking for Russian looks for
   *  "Русский", not for "Russian". */
  readonly nativeName: string;
  readonly direction: Direction;
};

export const LOCALES: readonly LocaleDefinition[] = [
  { code: 'en', nativeName: 'English', direction: 'ltr' },
  { code: 'hi', nativeName: 'हिन्दी', direction: 'ltr' },
  { code: 'ru', nativeName: 'Русский', direction: 'ltr' },
  { code: 'ja', nativeName: '日本語', direction: 'ltr' },
];

export const SOURCE_LOCALE = 'en';

export const DEFAULT_LOCALE = SOURCE_LOCALE;

export function isSupported(locale: string): boolean {
  return LOCALES.some((definition) => definition.code === locale);
}

export function directionOf(locale: string): Direction {
  return LOCALES.find((definition) => definition.code === locale)?.direction ?? 'ltr';
}

/**
 * The best match for a browser's `Accept-Language`-shaped preference list.
 *
 * Deliberately exact on the primary subtag and nothing more: `en-GB` is English, `pt-BR` is not a launch
 * locale, and a fallback chain that picks "the closest thing" would hand a Brazilian user a Spanish screen.
 * Failing to match is not a failure - it means English, which is what the spec says.
 */
export function bestMatch(preferred: readonly string[], supported: readonly string[]): string {
  for (const tag of preferred) {
    const primary = tag.split('-')[0]?.toLowerCase() ?? '';

    if (supported.includes(primary)) {
      return primary;
    }
  }

  return DEFAULT_LOCALE;
}

/**
 * Which locale can actually be rendered, and whether that differs from what was asked for.
 *
 * `docs/specs/i18n.md` section 8, point 4: machine translation may seed a locale, must be marked unreviewed in
 * `_meta.json`, and **the app refuses to render an unreviewed locale on money-moving and approval screens** -
 * falling back to English with a visible notice. This is that rule as a function: given the catalogues' review
 * state and whether the screen moves money, it answers which locale to render and whether to say so.
 */
export function renderable(
  requested: string,
  reviewState: ReadonlyMap<string, boolean>,
  options: { readonly requireReviewed: boolean },
): { readonly locale: string; readonly fellBack: boolean } {
  if (!options.requireReviewed) {
    return { locale: requested, fellBack: false };
  }

  if (reviewState.get(requested) ?? false) {
    return { locale: requested, fellBack: false };
  }

  return { locale: DEFAULT_LOCALE, fellBack: requested !== DEFAULT_LOCALE };
}
