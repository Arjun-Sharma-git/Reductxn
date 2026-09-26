import type { CurrencyInfo } from '@/entities/currency';
import type { NettingPreview } from '@/entities/netting';
import { useTranslations } from '@/shared/i18n';
import { Amount, Card } from '@/shared/ui';

import styles from './tables.module.css';

/**
 * Every member's position after netting, in the currency they hold it in.
 *
 * The direction is a word (`pay`, `receive`, `flat`) rather than a sign or a colour, which is the rule from the
 * skill's first category applied to a ledger: a reader who cannot distinguish red from green still knows which
 * way the money goes, and so does a screen reader, which never had a colour to read in the first place.
 */
export function PositionsTable({
  positions,
  catalog,
}: {
  readonly positions: NonNullable<NettingPreview['bestCase']>['positions'];
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t } = useTranslations();

  return (
    <Card title={t('netting.positions.title')} description={t('netting.positions.description')}>
      <table className={styles.table}>
        <caption className={styles.caption}>{t('netting.positions.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('netting.positions.member')}</th>
            <th scope="col">{t('netting.positions.direction')}</th>
            <th scope="col" className={styles.numeric}>
              {t('netting.positions.net')}
            </th>
          </tr>
        </thead>
        <tbody>
          {(positions ?? []).map((position) => (
            <tr key={`${position.userId}-${position.net.currency}`} data-reveal>
              <th scope="row" className={styles.rowHeader}>
                {position.userId}
              </th>
              <td>
                <span className={styles[position.direction]}>{t(`netting.direction.${position.direction}`)}</span>
              </td>
              <td className={styles.numeric}>
                <Amount value={position.net} catalog={catalog} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
