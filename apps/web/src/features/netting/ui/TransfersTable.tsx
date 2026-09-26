import type { CurrencyInfo } from '@/entities/currency';
import type { NettingPreview } from '@/entities/netting';
import { useTranslations } from '@/shared/i18n';
import { Amount, Card } from '@/shared/ui';

import styles from './tables.module.css';

/**
 * Who pays whom, which is the question a member actually opens this screen with.
 *
 * The FX leg is called out rather than hidden: a transfer whose payee is the clearing account is not a payment
 * to a member, it is the part of the plan that crosses currencies, and ADR 0012 puts it in the report precisely
 * so the cost of a multicurrency cycle is visible rather than folded into somebody's transfer.
 */
export function TransfersTable({
  transfers,
  catalog,
}: {
  readonly transfers: NonNullable<NettingPreview['bestCase']>['transfers'];
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t } = useTranslations();

  return (
    <Card title={t('netting.transfers.title')} description={t('netting.transfers.description')}>
      <table className={styles.table}>
        <caption className={styles.caption}>{t('netting.transfers.caption')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('netting.transfers.payer')}</th>
            <th scope="col">{t('netting.transfers.payee')}</th>
            <th scope="col" className={styles.numeric}>
              {t('netting.transfers.amount')}
            </th>
            <th scope="col">{t('netting.transfers.status')}</th>
          </tr>
        </thead>
        <tbody>
          {(transfers ?? []).map((transfer) => (
            <tr
              key={`${transfer.fromUserId}-${transfer.toUserId}-${transfer.amount.currency}`}
              data-reveal
            >
              <th scope="row" className={styles.rowHeader}>
                {transfer.fromUserId}
              </th>
              <td>
                {transfer.toAccountType === 'fx_clearing'
                  ? t('netting.transfers.viaFxClearing', { payee: transfer.toUserId })
                  : transfer.toUserId}
              </td>
              <td className={styles.numeric}>
                <Amount value={transfer.amount} catalog={catalog} />
              </td>
              <td>{t(`netting.status.${transfer.status}`)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}
