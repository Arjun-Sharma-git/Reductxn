#!/usr/bin/env node
/*
 * The locale gate: I1, I2, I3 and I7 from `docs/specs/i18n.md` section 10, plus the review state that section 8
 * turns into behaviour.
 *
 * MASTER-PLAN section 8 lists "Locale coverage | catalog check | a key is missing versus en, unused, or a literal
 * appears in a component (F10, I1-I3)" and "Error-code parity | codegen check | ... **Not built**" - this file is
 * the half of that gate that reads the catalogs; the literal half is the ESLint rule in `eslint.config.js`. There
 * is no CI configuration in this repository yet (a finding recorded in MASTER-PLAN), so the check runs from
 * `pnpm check` and would be one step in whatever pipeline is added.
 *
 *   I1  every `en` key exists in every shipped locale, **or the locale is flagged incomplete**. A locale with
 *       `complete: false` in `_meta.json` is reported rather than failed, which is what makes a partial
 *       translation possible without either lying about it or switching the check off.
 *   I2  no unused keys in `en`. A key nobody asks for is a key a translator is paid to write and nobody reads.
 *   I3  every server error `code` has an `errors` entry, and every `errors` entry has a server code. The codes
 *       are collected from the source rather than listed here: a hand-written list of codes goes stale silently,
 *       which is exactly why every `DomainError` declares its code as a constant.
 *   I7  a plural message carries every category its locale requires, with the categories coming from
 *       `Intl.PluralRules` rather than from a table in this file - Russian's four forms are a fact about Russian,
 *       and a table here would be a second, ageing copy of it.
 *
 * Usage: node tools/check-catalogs.mjs   (from apps/web)
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const root = new URL('..', import.meta.url).pathname;
const localesDir = join(root, 'locales');
const sourceLocale = 'en';

/** Namespaces the *client* owns: their codes have no server constant to match, so I3 does not apply to them. */
const clientNamespaces = ['client.', 'amount.'];

const failures = [];
const reports = [];

const fail = (message) => failures.push(message);
const report = (message) => reports.push(message);

function flatten(prefix, value, into) {
  if (typeof value === 'string') {
    into.set(prefix, value);

    return;
  }

  if (typeof value === 'object' && value !== null) {
    for (const [key, nested] of Object.entries(value)) {
      if (!key.startsWith('$') && !key.startsWith('//')) {
        flatten(prefix === '' ? key : `${prefix}.${key}`, nested, into);
      }
    }
  }
}

const namespacesOf = (locale) =>
  readdirSync(join(localesDir, locale))
    .filter((name) => name.endsWith('.json'))
    .map((name) => name.replace(/\.json$/, ''));

const meta = JSON.parse(readFileSync(join(localesDir, '_meta.json'), 'utf8')).locales ?? {};
const locales = readdirSync(localesDir).filter((name) => statSync(join(localesDir, name)).isDirectory());

const catalogs = new Map();

for (const locale of locales) {
  const messages = new Map();

  for (const namespace of namespacesOf(locale)) {
    const file = new Map();

    flatten('', JSON.parse(readFileSync(join(localesDir, locale, `${namespace}.json`), 'utf8')), file);

    for (const [key, value] of file) {
      messages.set(`${namespace}.${key}`, value);
    }
  }

  catalogs.set(locale, messages);
}

const source = catalogs.get(sourceLocale) ?? new Map();

// --- I1: every source key exists in every shipped locale, unless it is flagged incomplete -------------

for (const [locale, messages] of catalogs) {
  if (locale === sourceLocale) {
    continue;
  }

  const missing = [...source.keys()].filter((key) => !messages.has(key));

  if (missing.length === 0) {
    continue;
  }

  const described = `${locale} is missing ${missing.length} key(s) versus ${sourceLocale}: ${missing
    .slice(0, 6)
    .join(', ')}${missing.length > 6 ? ', …' : ''}`;

  if (meta[locale]?.complete === false) {
    report(`I1: ${described} - reported, not failed: _meta.json flags ${locale} complete:false, and the app falls back to ${sourceLocale} per key`);
  } else {
    fail(`I1: ${described}`);
  }
}

// --- I2: no unused keys in the source locale ---------------------------------------------------------

function sourceFiles(directory) {
  return readdirSync(directory).flatMap((name) => {
    const path = join(directory, name);

    if (statSync(path).isDirectory()) {
      return sourceFiles(path);
    }

    return /\.(ts|tsx)$/.test(name) && !name.endsWith('.d.ts') ? [path] : [];
  });
}

const asked = new Set();
const askedPrefixes = new Set();

for (const file of sourceFiles(join(root, 'src'))) {
  const text = readFileSync(file, 'utf8');

  for (const match of text.matchAll(/\bt\(\s*['"]([A-Za-z0-9_.]+)['"]/g)) {
    asked.add(match[1]);
  }

  // A key built from data - `` t(`netting.direction.${direction}`) `` - is matched by its literal prefix. Every
  // key under that prefix counts as used, because the alternative is failing on the one legitimate, type-checked
  // way to name a message per enum value.
  for (const match of text.matchAll(/\bt\(\s*`([^`$]*)\$\{/g)) {
    askedPrefixes.add(match[1]);
  }
}

const unused = [...source.keys()].filter(
  (key) => !asked.has(key) && ![...askedPrefixes].some((prefix) => key.startsWith(prefix)),
);

if (unused.length > 0) {
  fail(`I2: ${unused.length} unused key(s) in ${sourceLocale}: ${unused.slice(0, 6).join(', ')}${unused.length > 6 ? ', …' : ''}`);
}

// --- I3: error codes and the catalog agree, in both directions ----------------------------------------

function serverCodes() {
  const codes = new Set();

  const walk = (directory) => {
    let entries;

    try {
      entries = readdirSync(directory);
    } catch {
      // A module that does not exist is not a failure here.
      return;
    }

    for (const name of entries) {
      const path = join(directory, name);

      if (statSync(path).isDirectory()) {
        walk(path);
      } else if (path.endsWith('.java') && path.includes('src/main/java')) {
        for (const match of readFileSync(path, 'utf8').matchAll(/CODE = "([a-z0-9_.]+)"/g)) {
          codes.add(match[1]);
        }
      }
    }
  };

  for (const prefix of ['../../libs', '../../apps/api', '../../workers', '../../platform']) {
    walk(join(root, prefix));
  }

  return codes;
}

const server = serverCodes();
const catalogCodes = new Set(
  [...source.keys()]
    .filter((key) => key.startsWith('errors.'))
    .map((key) => key.slice('errors.'.length))
    .filter((code) => !clientNamespaces.some((namespace) => code.startsWith(namespace))),
);

for (const code of server) {
  if (!catalogCodes.has(code)) {
    fail(`I3: the server can answer ${code} and ${sourceLocale}/errors.json has no entry for it`);
  }
}

for (const code of catalogCodes) {
  if (!server.has(code)) {
    fail(`I3: ${sourceLocale}/errors.json has an entry for ${code}, which no server error declares`);
  }
}

// --- I7: plural messages carry every category their locale requires ----------------------------------

/**
 * The body of each ICU plural block in a message.
 *
 * Brace matching rather than a regular expression, and this is not gold-plating: the first version of this check
 * used `[^}]*` to capture the block, which stops at the *first* closing brace - inside `one {# transfer}` - so it
 * reported that an English plural was missing `other` when the message plainly had it. A check that produces a
 * false failure is worse than no check, because the next person switches it off.
 */
function pluralBlocks(message) {
  const blocks = [];
  const marker = /\{\s*[A-Za-z0-9_]+\s*,\s*(?:plural|selectordinal)\s*,/g;

  for (const match of message.matchAll(marker)) {
    // Walk forward from the marker, counting braces: the block ends where its own opening brace closes, and the
    // opening brace is the one that started the match.
    let depth = 1;
    let index = match.index + match[0].length;

    while (index < message.length && depth > 0) {
      const character = message[index];

      if (character === '{') {
        depth += 1;
      } else if (character === '}') {
        depth -= 1;
      }

      index += 1;
    }

    blocks.push(message.slice(match.index + match[0].length, index - 1));
  }

  return blocks;
}

for (const [locale, messages] of catalogs) {
  const required = new Intl.PluralRules(locale).resolvedOptions().pluralCategories;

  for (const [key, message] of messages) {
    for (const block of pluralBlocks(message)) {
      const categories = [...block.matchAll(/(one|two|few|many|other|zero|=\d+)\s*\{/g)].map(
        (match) => match[1],
      );

      const missing = required.filter((category) => !categories.includes(category));

      if (missing.length === 0) {
        continue;
      }

      const described = `I7: ${locale} ${key} is missing the plural categor(ies) ${missing.join(', ')} that ${locale} requires (${required.join(', ')})`;

      if (meta[locale]?.complete === false) {
        report(`${described} - reported, not failed: _meta.json flags ${locale} complete:false`);
      } else {
        fail(described);
      }
    }
  }
}

// --- the verdict -------------------------------------------------------------------------------------

for (const line of reports) {
  console.log(`  ${line}`);
}

if (failures.length > 0) {
  console.error(`\nThe locale catalogs do not pass (${failures.length} problem(s)):\n`);

  for (const line of failures) {
    console.error(`  ${line}`);
  }

  console.error(`\n${source.size} keys in ${sourceLocale}, ${locales.length} locale(s), ${server.size} server error codes.`);

  process.exit(1);
}

console.log(
  `The locale catalogs pass: ${source.size} keys in ${sourceLocale}, ${locales.length} locale(s), ` +
    `${server.size} server error codes, ${asked.size} keys asked for by name in the source.`,
);
