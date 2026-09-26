import { IntlMessageFormat } from 'intl-messageformat';

import type { Catalog } from './catalog';
import { sourceCatalog } from './catalog';
import { SOURCE_LOCALE } from './locales';

/**
 * ICU, and nothing else.
 *
 * The spec's argument for ICU is one sentence long and unanswerable: English has two plural forms, Russian has
 * four, Japanese has one - so any string built by concatenating a number and a noun is wrong in at least one
 * launch locale. `IntlMessageFormat` parses a message once and caches it, which is also why a message may
 * reorder its arguments freely: a translator who needs the amount before the name can move it.
 *
 * Two fallbacks, and they are different:
 *
 *   - a key that exists in `en` and not in this locale renders the English message: a half-translated locale
 *     should show a screen with a few English sentences in it, not a screen with holes in it;
 *   - a key that exists nowhere renders the key itself, because that is unambiguously a bug and looks like one.
 *
 * The second case is normally unreachable: `tools/check-catalogs.mjs` fails the build when a key used in the
 * source is missing from `en` (RULE F10, I2).
 */

export type Values = Readonly<Record<string, string | number | Date>>;

const formats = new Map<string, IntlMessageFormat>();

function compiled(locale: string, message: string): IntlMessageFormat {
  const key = `${locale}\u0000${message}`;
  const cached = formats.get(key);

  if (cached !== undefined) {
    return cached;
  }

  // `ignoreTag` is deliberate: the catalogue is not HTML. A message interpolating user data - a member's name, a
  // memo - must never be parsed as markup, and ICU's tag support exists for a case this product does not have.
  const format = new IntlMessageFormat(message, locale, undefined, { ignoreTag: true });

  formats.set(key, format);

  return format;
}

/**
 * One message, in one locale.
 *
 * @param key `namespace.key`, as the catalogs are keyed
 */
export function translate(locale: string, catalog: Catalog, key: string, values: Values = {}): string {
  const own = catalog.get(key);
  const message = own ?? sourceCatalog().get(key);

  if (message === undefined) {
    return key;
  }

  // Which locale's rules the message is formatted *with*: the requested one when the message is that locale's, and
  // the source when the message is a fallback - because plural categories belong to the language the sentence is
  // written in, not to the reader. Formatting an English sentence with Japanese rules produced "1 currencies"
  // (Japanese has one category, so the English `one` form is never selected), which the plural test caught: the
  // fallback has to be English all the way down, or a partial locale reads as broken grammar rather than as
  // missing translation.
  const language = own === undefined ? SOURCE_LOCALE : locale;

  try {
    return compiled(language, message).format(values).toString();
  } catch {
    /*
     * A message that cannot be formatted must not take the screen down.
     *
     * The case that found this: `ledger.currency_mismatch` is documented as carrying `{expected}` and `{actual}`,
     * and a server that answers without them (or a test stub that does) made `IntlMessageFormat` throw inside a
     * render - which unmounts the tree and leaves a member looking at nothing. Showing the message with its
     * placeholders visible is worse than showing it fully and much better than showing a blank page, and it is
     * self-diagnosing: the placeholders name the parameters that are missing.
     */
    return message;
  }
}

/**
 * A date, in the reader's own locale and zone.
 *
 * The group's timezone decides *which cycle* an obligation falls into (`docs/specs/i18n.md` section 7), and it
 * is not this function's business: the boundary belongs to the group, while the rendering of an instant belongs
 * to the reader. So no zone is passed here, and the browser's own is used - with `timeZone` available for the
 * screens that must show the boundary in the group's terms.
 */
export function formatDateTime(instant: string | Date, locale: string, timeZone?: string): string {
  const value = typeof instant === 'string' ? new Date(instant) : instant;

  return new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
    timeStyle: 'short',
    ...(timeZone === undefined ? {} : { timeZone }),
  }).format(value);
}

/** Counts, percentages and other non-money numbers. Money has its own module, and this is not it. */
export function formatNumber(value: number, locale: string): string {
  return new Intl.NumberFormat(locale).format(value);
}

/** The source language's message for a key, for the catalog check and for tests. */
export function sourceMessage(key: string): string | undefined {
  return sourceCatalog().get(key);
}

export const SOURCE = SOURCE_LOCALE;
