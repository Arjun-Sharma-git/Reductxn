import type { Money } from '@/shared/money';
import { formatMoneyOrRefusal } from '@/shared/money';

import { useTranslations } from '@/shared/i18n';

import styles from './Amount.module.css';

/**
 * The only component that renders money, and it renders it through `shared/money`.
 *
 * That is RULE F9 and RULE F11 in one component: a screen passes the value the server sent and the exponent it
 * fetched, and this is where the two meet. Nobody writes `amount.amountMinor / 100`, because nobody else has to
 * think about what an amount is - which is the point of there being one place.
 *
 * The zero-exponent case (JPY, exponent 0) and the three-digit grouping case (`en-IN`) are both the same code
 * path here, and both have a test: the exponent is data, not a guess, and the grouping comes from the locale
 * rather than from a formatter's default.
 */
export function Amount({
  value,
  catalog,
  emphasis = 'normal',
}: {
  readonly value: Money;
  readonly catalog: ReadonlyMap<string, { readonly minorUnitExponent: number }>;
  readonly emphasis?: 'normal' | 'strong';
}) {
  const { locale, t } = useTranslations();
  const formatted = formatMoneyOrRefusal(value, locale, catalog);

  if (!formatted.ok) {
    // A currency the client's copy of the catalogue does not know. Shown as an explicit "not yet available"
    // rather than as the raw integer, which a reader could mistake for a formatted amount.
    return (
      <span className={styles.unknown} title={t('common.unknownCurrencyTitle', { currency: value.currency })}>
        {t('common.unknownCurrency', { currency: value.currency })}
      </span>
    );
  }

  return (
    <span className={`rn-amount ${emphasis === 'strong' ? styles.strong : ''}`}>{formatted.value}</span>
  );
}
