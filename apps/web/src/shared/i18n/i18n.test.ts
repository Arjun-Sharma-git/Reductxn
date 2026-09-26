import { describe, expect, it } from 'vitest';

import { availableLocales, catalogFor, reviewStateOf, sourceCatalog } from './catalog';
import { LOCALES, bestMatch, directionOf, renderable } from './locales';
import { formatDateTime, translate } from './translate';

/**
 * The i18n layer, and the three claims worth checking rather than asserting in prose.
 *
 * **Russian needs four plural forms and English needs two.** That is the whole argument for ICU, and it is
 * checked by formatting the same message at counts that select each category - 1, 2, 5 and 21 for Russian - rather
 * than by reading the catalog and trusting it.
 *
 * **A missing key falls back per key, not per locale.** A half-translated locale shows a screen with a few
 * English sentences in it, rather than a screen full of `netting.transfers.payer`. The second behaviour is what a
 * naive `t()` does, and it is worse than the English.
 *
 * **An unreviewed locale is not rendered where money is involved** (`docs/specs/i18n.md` section 8.4). The rule
 * is a function, and the function has a test: ru is seeded and unreviewed, so a screen that demands a reviewed
 * translation gets English and a flag saying so.
 */
describe('locales', () => {
  it('matches a browser preference list to a supported locale', () => {
    expect(bestMatch(['ru-RU', 'en-GB'], ['en', 'ru'])).toBe('ru');
    expect(bestMatch(['pt-BR', 'fr'], ['en', 'ru'])).toBe('en');
    expect(bestMatch([], ['en'])).toBe('en');
  });

  it('knows which locales are written and which are reserved', () => {
    expect(LOCALES.map((locale) => locale.code)).toEqual(['en', 'hi', 'ru', 'ja']);
    expect(directionOf('en')).toBe('ltr');
    expect(availableLocales()).toEqual(['en', 'ru']);
  });

  it('refuses an unreviewed locale on a screen that demands a reviewed one', () => {
    const state = reviewStateOf();

    expect(renderable('ru', state, { requireReviewed: false })).toEqual({ locale: 'ru', fellBack: false });
    expect(renderable('ru', state, { requireReviewed: true })).toEqual({ locale: 'en', fellBack: true });
    expect(renderable('en', state, { requireReviewed: true })).toEqual({ locale: 'en', fellBack: false });
  });
});

describe('ICU messages', () => {
  it('selects Russian’s four plural categories', () => {
    const ru = catalogFor('ru');

    expect(translate('ru', ru, 'netting.partitions.summary', { count: 1, currencies: 1 })).toContain('1 перевод');
    expect(translate('ru', ru, 'netting.partitions.summary', { count: 2, currencies: 1 })).toContain('2 перевода');
    expect(translate('ru', ru, 'netting.partitions.summary', { count: 5, currencies: 1 })).toContain('5 переводов');
    // 21 is `one` in Russian, which is the case a two-form catalog gets wrong.
    expect(translate('ru', ru, 'netting.partitions.summary', { count: 21, currencies: 1 })).toContain('21 перевод');
  });

  it('selects English’s two categories', () => {
    const en = sourceCatalog();

    expect(translate('en', en, 'netting.partitions.summary', { count: 1, currencies: 2 })).toBe(
      '1 transfer across 2 currencies',
    );
    expect(translate('en', en, 'netting.partitions.summary', { count: 3, currencies: 1 })).toBe(
      '3 transfers across 1 currency',
    );
  });

  it('uses a locale’s plural rules even when the message came from the source catalog', () => {
    // Japanese has one category (`other`), so the English message renders with `other` selected - which is what
    // makes a partial locale usable without a partial translation being *wrong* about grammar.
    expect(translate('ja', new Map(), 'netting.partitions.summary', { count: 2, currencies: 1 })).toBe(
      '2 transfers across 1 currency',
    );
  });

  it('falls back to English per key and, failing that, says which key is missing', () => {
    const partial = new Map([['netting.title', 'Предварительный расчёт']]);

    expect(translate('ru', partial, 'netting.title')).toBe('Предварительный расчёт');
    expect(translate('ru', partial, 'netting.empty')).toBe('This group has no open commitments, so there is nothing to settle.');
    expect(translate('ru', partial, 'netting.noSuchKey')).toBe('netting.noSuchKey');
  });

  it('formats an instant in the reader’s locale', () => {
    const instant = '2026-09-25T14:00:00Z';

    // Measured rather than invented: `en-IN` writes the month as "Sept" and puts the day first.
    expect(formatDateTime(instant, 'en-IN', 'UTC')).toBe('25 Sept 2026, 2:00 pm');
    expect(formatDateTime(instant, 'ja', 'UTC')).toContain('2026');
  });
});
