import type { Partition } from '@/entities/netting';
import type { CurrencyInfo } from '@/entities/currency';
import { useTranslations } from '@/shared/i18n';
import { Amount, Card } from '@/shared/ui';

import styles from './tables.module.css';

/**
 * What netting saves, per currency.
 *
 * The columns are the three figures that answer the question the product asks: how many transfers there were,
 * how many there are, and what the difference is worth in tax. Nothing is added across rows - a column of
 * rupees summed with a column of yen is the arithmetic ADR 0012 forbids - and the footnote says so, because a
 * reader looking at a column of money will reach for the total.
 */
export function PartitionsTable({
  partitions,
  catalog,
}: {
  readonly partitions: readonly Partition[];
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t } = useTranslations();

  return (
    <Card title={t('netting.partitions.title')} description={t('netting.partitions.description')}>
      <table className={styles.table}>
        <caption className={styles.caption}>{t('netting.partitions.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('netting.partitions.currency')}</th>
            <th scope="col" className={styles.numeric}>
              {t('netting.partitions.transfers')}
            </th>
            <th scope="col" className={styles.numeric}>
              {t('netting.partitions.tax')}
            </th>
            <th scope="col" className={styles.numeric}>
              {t('netting.partitions.saved')}
            </th>
          </tr>
        </thead>
        <tbody>
          {partitions.map((partition) => (
            <tr key={partition.currency} data-reveal>
              <th scope="row" className={styles.rowHeader}>
                {partition.currency}
              </th>
              <td className={styles.numeric}>
                {t('netting.partitions.transferCounts', {
                  gross: partition.grossTransferCount,
                  net: partition.netTransferCount,
                })}
              </td>
              <td className={styles.numeric}>
                <Amount value={partition.netTax} catalog={catalog} />
              </td>
              <td className={styles.numeric}>
                <Amount value={partition.taxSaved} catalog={catalog} emphasis="strong" />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className={styles.footnote}>
        {t('netting.partitions.summary', {
          count: partitions.reduce((total, partition) => total + partition.netTransferCount, 0),
          currencies: partitions.length,
        })}
      </p>
      <p className={styles.footnote}>{t('netting.partitions.perCurrencyNote')}</p>
    </Card>
  );
}
