import type { CurrencyInfo } from '@/entities/currency';
import { nameOf, type Member } from '@/entities/member';
import type { Obligation } from '@/entities/obligation';
import { useTranslations } from '@/shared/i18n';
import { Amount } from '@/shared/ui';

import { isPending } from '../mutations';
import styles from './ObligationsScreen.module.css';

/**
 * The commitments, newest first, with names rather than identifiers.
 *
 * Two columns carry a decision rather than a value:
 *
 *   - **The settlement column** says whether the creditor is paid in the currency the commitment was recorded in
 *     or in another one. That is the difference between a single-currency obligation and a cross-currency one, and
 *     it is the fact ADR 0012 turns on - a member reading a row should be able to see that a conversion is part of
 *     it without opening anything.
 *   - **The status column** shows "recording" for a row that exists in this browser and not yet on the server. An
 *     optimistic row that looks like every other row is a row the group appears to have agreed to before it has.
 */
export function ObligationRows({
  rows,
  members,
  catalog,
}: {
  readonly rows: readonly Obligation[];
  readonly members: readonly Member[];
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t, dateTime } = useTranslations();

  return (
    <table className={styles.table}>
      <caption className={styles.caption}>{t('obligations.list.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">{t('obligations.list.debtor')}</th>
          <th scope="col">{t('obligations.list.creditor')}</th>
          <th scope="col" className={styles.numeric}>
            {t('obligations.list.amount')}
          </th>
          <th scope="col">{t('obligations.list.settlement')}</th>
          <th scope="col">{t('obligations.list.memo')}</th>
          <th scope="col">{t('obligations.list.status')}</th>
          <th scope="col">{t('obligations.list.occurredAt')}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((obligation) => (
          <tr key={obligation.id} data-reveal className={isPending(obligation) ? styles.pending : undefined}>
            <th scope="row" className={styles.rowHeader}>
              {nameOf(members, obligation.debtorId)}
            </th>
            <td>{nameOf(members, obligation.creditorId)}</td>
            <td className={styles.numeric}>
              <Amount value={obligation.amount} catalog={catalog} />
            </td>
            <td>
              {obligation.settleInCurrency === undefined ||
              obligation.settleInCurrency === obligation.amount.currency
                ? t('obligations.list.settlementSame')
                : t('obligations.list.settlementDifferent', { currency: obligation.settleInCurrency })}
            </td>
            <td className={styles.memo}>{obligation.memo ?? ''}</td>
            <td>
              {isPending(obligation)
                ? t('obligations.list.recording')
                : t(`obligations.status.${obligation.status}`)}
            </td>
            <td>{dateTime(obligation.occurredAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
