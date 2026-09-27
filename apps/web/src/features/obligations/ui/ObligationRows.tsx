import { useState } from 'react';

import type { CurrencyInfo } from '@/entities/currency';
import { nameOf, type Member } from '@/entities/member';
import type { Obligation } from '@/entities/obligation';
import { useTranslations } from '@/shared/i18n';
import { Amount, ProblemNotice } from '@/shared/ui';

import { isPending, useVoidObligation } from '../mutations';
import styles from './ObligationsScreen.module.css';

/**
 * The commitments, newest first, with names rather than identifiers.
 *
 * Three columns carry a decision rather than a value:
 *
 *   - **The settlement column** says whether the creditor is paid in the currency the commitment was recorded in
 *     or in another one. That is the difference between a single-currency obligation and a cross-currency one, and
 *     it is the fact ADR 0012 turns on - a member reading a row should be able to see that a conversion is part of
 *     it without opening anything.
 *   - **The status column** shows "recording" for a row that exists in this browser and not yet on the server. An
 *     optimistic row that looks like every other row is a row the group appears to have agreed to before it has.
 *   - **The actions column** is the void, and it exists on exactly the rows the server would accept it for. Asking
 *     for a commitment a run holds is a `409`, so offering the button on a `netted` or `settled` row would be the
 *     screen inviting a refusal - and offering it on a row that has not reached the server yet would be a request
 *     for an id the server has never seen (ADR 0045).
 *
 * **The void is a second press, not one press.** It cannot be undone: the row stays, marked `voided`, and the plan
 * says a commitment is corrected rather than deleted. So the first press asks and the second does it, in the row the
 * member is looking at. A native `confirm` was the alternative and it is worse than it looks - it blocks the render,
 * it cannot be translated by this catalog, and it is suppressed outright in some browsers.
 */
export function ObligationRows({
  groupId,
  rows,
  members,
  catalog,
}: {
  readonly groupId: string;
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
          <th scope="col">{t('obligations.list.actions')}</th>
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
            <td className={styles.actions}>
              <VoidAction groupId={groupId} obligation={obligation} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The void, for one row: a question, then the request, or the reason it was refused.
 *
 * The mutation is created per row rather than once for the table, so `isPending` belongs to the row that was
 * pressed: a loop over rows sharing one mutation would grey out every row's button when any of them was voiding.
 * The cost is one observer per row, which is what the table already pays for its rows.
 */
function VoidAction({ groupId, obligation }: { groupId: string; obligation: Obligation }) {
  const { t } = useTranslations();
  const [asking, setAsking] = useState(false);
  const voidOne = useVoidObligation(groupId);

  if (voidOne.isPending) {
    return <span className={styles.noAction}>{t('obligations.list.voidSending')}</span>;
  }

  // A row the server has not confirmed has no id it would recognise, and a row a run holds or has taken cannot be
  // voided at all. Both say so rather than offering a button that would be refused.
  if (isPending(obligation) || obligation.status !== 'open') {
    return <span className={styles.noAction}>{t('obligations.list.nothingToDo')}</span>;
  }

  if (!asking) {
    return (
      <button type="button" className="rn-button" onClick={() => setAsking(true)}>
        {t('obligations.list.void')}
      </button>
    );
  }

  return (
    <div className={styles.confirm}>
      <p className={styles.question}>{t('obligations.list.voidQuestion')}</p>
      <div className={styles.confirmButtons}>
        <button type="button" className="rn-button" onClick={() => voidOne.mutate(obligation.id)}>
          {t('obligations.list.voidConfirm')}
        </button>
        <button
          type="button"
          className={`rn-button ${styles.keep}`}
          onClick={() => setAsking(false)}
        >
          {t('obligations.list.voidCancel')}
        </button>
      </div>

      {voidOne.isError ? (
        <>
          <p className={styles.question}>{t('obligations.list.voidRefused')}</p>
          <ProblemNotice fault={voidOne.error} />
        </>
      ) : null}
    </div>
  );
}
