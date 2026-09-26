/**
 * `shared/i18n`: the locale, the ICU catalogues, and the two rules that keep text honest.
 *
 * RULE F10 (no user-facing literal in a component) and RULE F11 (formatting goes through this layer or
 * `shared/money`) are enforced by ESLint rather than by this file, but the shape of the exports is part of the
 * same intent: a component gets a `t` from `useTranslations()` and has no other way to produce a sentence.
 */
export {
  bestMatch,
  DEFAULT_LOCALE,
  directionOf,
  isSupported,
  LOCALES,
  renderable,
  SOURCE_LOCALE,
  type Direction,
  type LocaleDefinition,
} from './locales';
export {
  availableLocales,
  catalogFor,
  namespacesIn,
  reviewStateOf,
  sourceCatalog,
  type Catalog,
} from './catalog';
export { formatDateTime, formatNumber, sourceMessage, translate, type Values } from './translate';
export { LocaleProvider, useTranslations, type Translations } from './LocaleProvider';
