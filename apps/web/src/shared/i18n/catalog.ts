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

const files = import.meta.glob<Record<string, unknown>>('/locales/*/*.json', { eager: true });

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
  const locale = localeOf(path);

  if (locale.startsWith('_')) {
    // `_meta.json` is the review state, not messages.
    const state = module as { locales?: Record<string, { reviewed?: boolean }> };

    for (const [code, meta] of Object.entries(state.locales ?? {})) {
      reviewState.set(code, meta.reviewed === true);
    }

    continue;
  }

  const namespace = namespaceOf(path);

  const catalog = catalogs.get(locale) ?? new Map<string, string>();
  const names = new Set(namespaces.get(locale) ?? []);

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

  flatten('', module);

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
