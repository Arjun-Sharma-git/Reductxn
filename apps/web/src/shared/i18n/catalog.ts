import { SOURCE_LOCALE } from './locales';

/**
 * The ICU catalogues, loaded from `apps/web/locales`.
 *
 * Ordered the way the spec describes them (`apps/web/locales/<locale>/<namespace>.json`, flat ICU messages
 * keyed by namespace), and loaded with a glob rather than a hand-written import list. The glob is the point: a
 * new namespace file is *in* the catalogue the moment it exists, and a translator adding a locale adds a
 * directory rather than an entry in a registry that some other file has to be told about.
 *
 * Keys are flattened to `namespace.key` - the JSON's nesting is for a translator's eye, and the ICU message is
 * the value. That matches the spec's example, `"obligations.countMembers"`.
 */

/**
 * Two glob patterns, because there are two shapes of file here: the top-level meta file (`_meta.json`, the review
 * state) and the per-locale namespace files (` locales / <locale> / <namespace>.json `).
 *
 * The first version had only the namespace pattern, so the meta file was never read, the review state was empty,
 * and every locale was treated as unreviewed - which made the review-state test pass for the wrong reason (its
 * subject, `ru`, is unreviewed either way) while `en` would also have been refused on a money screen.
 *
 * (The patterns are described in words rather than written out here on purpose: a block comment cannot contain the
 * closing sequence those globs end with, and writing it made the rest of this file code.)
 */
const files = import.meta.glob<Record<string, unknown>>(['/locales/*.json', '/locales/*/*.json'], {
  eager: true,
});

/** Every message a locale has, keyed `namespace.key`. */
export type Catalog = ReadonlyMap<string, string>;

const catalogs = new Map<string, Map<string, string>>();
const reviewState = new Map<string, boolean>();
const namespaces = new Map<string, ReadonlySet<string>>();

function localeOf(path: string): string {
  const parts = path.split('/');

  return parts.at(-2) ?? '';
}

function namespaceOf(path: string): string {
  return (path.split('/').at(-1) ?? '').replace(/\.json$/, '');
}

for (const [path, module] of Object.entries(files)) {
  const namespace = namespaceOf(path);

  if (namespace.startsWith('_')) {
    // `_meta.json` is the review state, not messages. Keyed on the *file*, not on the directory: the first
    // version of this checked the directory name, so `locales/_meta.json` looked like a locale called `locales`
    // and the review state was silently empty - every locale was treated as unreviewed, which happened to make
    // the review test pass for the wrong reason.
    // The same unwrapping as below, and for the same reason: the bundler's module shape is not the file.
    const meta = (module as { default?: { locales?: Record<string, { reviewed?: boolean }> } }).default ?? module;
    const state = meta as { locales?: Record<string, { reviewed?: boolean }> };

    for (const [code, entry] of Object.entries(state.locales ?? {})) {
      reviewState.set(code, entry.reviewed === true);
    }

    continue;
  }

  const locale = localeOf(path);

  const catalog = catalogs.get(locale) ?? new Map<string, string>();
  const names = new Set(namespaces.get(locale) ?? []);

  /*
   * The JSON's own object, not the module the bundler made from it.
   *
   * Vite turns a JSON import into an ES module with a named export per top-level key - and a JavaScript identifier
   * cannot contain a dot, so a key like `localeName.en` or `api.decision_already_answered` has no named export and
   * exists *only* inside `default`. Walking the module namespace therefore produced `common.default.localeName.en`
   * for exactly the keys a template literal needs (`t(`common.localeName.${code}`)`), and every server error message
   * keyed by a dotted code went missing - which `ProblemNotice` degraded gracefully into "no wording for this code
   * yet", so nothing looked broken enough to notice for a whole slice.
   *
   * Unwrapping `default` gives the file as written. The catalog check reads the JSON files directly and so could
   * never have caught this; the i18n test that asserts a *dotted* key does.
   */
  const document = (module as { default?: Record<string, unknown> }).default ?? module;

  // Flattened recursively: a translator groups keys by nesting (`"openGroup": { "title": ... }`), and a message
  // is always a string leaf. The key a component asks for is the path - `common.openGroup.title` - which is the
  // shape the spec's own example uses.
  const flatten = (prefix: string, value: unknown): void => {
    if (typeof value === 'string') {
      catalog.set(`${namespace}.${prefix}`, value);

      return;
    }

    if (typeof value === 'object' && value !== null) {
      for (const [key, nested] of Object.entries(value)) {
        if (!key.startsWith('$') && !key.startsWith('//')) {
          flatten(prefix === '' ? key : `${prefix}.${key}`, nested);
        }
      }
    }
  };

  flatten('', document);

  if (catalog.size > 0) {
    names.add(namespace);
  }

  catalogs.set(locale, catalog);
  namespaces.set(locale, names);
}

/** The messages for a locale. Empty for a locale nobody has written yet, which is a state the notice explains. */
export function catalogFor(locale: string): Catalog {
  return catalogs.get(locale) ?? new Map<string, string>();
}

export function sourceCatalog(): Catalog {
  return catalogFor(SOURCE_LOCALE);
}

/** Which locales have been written at all. */
export function availableLocales(): readonly string[] {
  return [...catalogs.keys()].sort();
}

export function reviewStateOf(): ReadonlyMap<string, boolean> {
  return reviewState;
}

export function namespacesIn(locale: string): ReadonlySet<string> {
  return namespaces.get(locale) ?? new Set<string>();
}
