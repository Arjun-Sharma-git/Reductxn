import type { CurrencyInfo } from '@/entities/currency';
import { nameOf, type Member } from '@/entities/member';
import type { Run, Transfer } from '@/entities/run';
import { useTranslations } from '@/shared/i18n';
import { Amount } from '@/shared/ui';

import { isKnownStatus, toneOf } from '../model/decision';
import styles from './SettlementScreens.module.css';

/**
 * The pieces both settlement screens draw: figures per currency, who pays whom, the answers on the record, and what
 * the run's gates concluded.
 *
 * They live in one file because they are one vocabulary - a member reads the same four shapes on the group's page and
 * on a run - and a component per file would be four files differing only in their heading.
 *
 * **The currency-clearing leg is named, never hidden.** A transfer whose destination is the group's FX clearing
 * account is the group's own currency conversion rather than anybody's payment (ADR 0012), and the read model labels
 * it (`toAccountType`). Rendering it as if a member received it would present the group's exposure as somebody's
 * debt - which is the mistake the label exists to prevent, made in the interface instead of on the wire.
 */

export function PartitionRows({
  run,
  catalog,
}: {
  readonly run: Run;
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t } = useTranslations();

  // Nothing adds rupees to yen: every figure is per currency, and a total across them is not a number.
  return (
    <table className={styles.table}>
      <caption className={styles.caption}>{t('settlement.partitions.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">{t('settlement.partitions.currency')}</th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.partitions.gross')}
          </th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.partitions.net')}
          </th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.partitions.tax')}
          </th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.partitions.taxSaved')}
          </th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.partitions.transfers')}
          </th>
        </tr>
      </thead>
      <tbody>
        {(run.partitions ?? []).map((partition) => (
          <tr key={partition.currency} data-reveal>
            <th scope="row" className={styles.rowHeader}>
              {partition.currency}
            </th>
            <td className={styles.numeric}>
              <Amount value={partition.gross} catalog={catalog} />
            </td>
            <td className={styles.numeric}>
              <Amount value={partition.net} catalog={catalog} />
            </td>
            <td className={styles.numeric}>
              <Amount value={partition.tax} catalog={catalog} />
            </td>
            <td className={styles.numeric}>
              <Amount value={partition.taxSaved} catalog={catalog} />
            </td>
            <td className={styles.numeric}>{partition.transferCount}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function TransferRows({
  transfers,
  members,
  catalog,
}: {
  readonly transfers: readonly Transfer[];
  readonly members: readonly Member[];
  readonly catalog: ReadonlyMap<string, CurrencyInfo>;
}) {
  const { t } = useTranslations();

  return (
    <table className={styles.table}>
      <caption className={styles.caption}>{t('settlement.transfers.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">{t('settlement.transfers.from')}</th>
          <th scope="col">{t('settlement.transfers.to')}</th>
          <th scope="col" className={styles.numeric}>
            {t('settlement.transfers.amount')}
          </th>
          <th scope="col">{t('settlement.transfers.status')}</th>
        </tr>
      </thead>
      <tbody>
        {transfers.map((transfer) => (
          <tr key={transfer.railRef} data-reveal>
            <th scope="row" className={styles.rowHeader}>
              {nameOf(members, transfer.fromUserId)}
            </th>
            <td>
              {transfer.toAccountType === 'fx_clearing'
                ? t('settlement.transfers.fxClearing')
                : nameOf(members, transfer.toUserId ?? '')}
            </td>
            <td className={styles.numeric}>
              <Amount value={transfer.amount} catalog={catalog} />
            </td>
            <td>
              {/* The rail's own words when it gave any, and no invented code: there is no stable failure vocabulary
                  to branch on yet, which the transfer read model says out loud. */}
              {transfer.failureReason === undefined || transfer.failureReason === null
                ? t(`settlement.transferStatus.${transfer.status}`)
                : `${t(`settlement.transferStatus.${transfer.status}`)}: ${transfer.failureReason}`}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export function AnswerRows({
  run,
  members,
}: {
  readonly run: Run;
  readonly members: readonly Member[];
}) {
  const { t, dateTime } = useTranslations();

  return (
    <table className={styles.table}>
      <caption className={styles.caption}>{t('settlement.answers.caption')}</caption>
      <thead>
        <tr>
          <th scope="col">{t('settlement.answers.member')}</th>
          <th scope="col">{t('settlement.answers.answer')}</th>
          <th scope="col">{t('settlement.answers.when')}</th>
        </tr>
      </thead>
      <tbody>
        {(run.answers ?? []).map((answer) => (
          <tr key={answer.memberId} data-reveal>
            <th scope="row" className={styles.rowHeader}>
              {nameOf(members, answer.memberId)}
            </th>
            <td>{t(`settlement.answer.${answer.decision}`)}</td>
            <td>{dateTime(answer.decidedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * A run's status, as a word a member can read.
 *
 * <p>The key is built from the status itself - `settlement.status.awaiting_approval` - so the vocabulary lives where it
 * is defined (the migration's `CHECK` constraint) rather than in a table here, and a translator can see the status
 * word in the catalog. A status this screen has not been told about renders the honest phrase for that case instead of
 * the raw word, which is what the read model's refusal to enumerate statuses requires of its readers.
 *
 * <p>The tone is decoration: the phrase carries the meaning, so a reader who cannot see the colour loses nothing.
 */
export function StatusBadge({ status }: { readonly status: string }) {
  const { t } = useTranslations();
  const tone = toneOf(status);

  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      {isKnownStatus(status) ? t(`settlement.status.${status}`) : t('settlement.status.unknown')}
    </span>
  );
}

/**
 * What the run's gates concluded, as the worker wrote it down.
 *
 * <p>The counts are the worker's, projected: "one approval short" is a sentence this screen can say because
 * {@code settlement_run_gates} holds the arithmetic (ADR 0023), and it does not compute it. When the worker has not
 * looked at the run there is nothing to show, and that is a different thing from a verdict that says everything is
 * satisfied - so the two are different sentences rather than an absent block.
 *
 * <p>{@code debtorsStillToConfirm} is the count that explains the case members find hardest ("enough approvals, still
 * waiting for the people who owe"), and it is a number rather than a list because the run's participants are not
 * published by this read model.
 */
export function Gates({ run }: { readonly run: Run }) {
  const { t } = useTranslations();

  if (run.gates === undefined || run.gates === null) {
    return <p className={styles.gates}>{t('settlement.gates.notYetLooked')}</p>;
  }

  const { quorum, funding } = run.gates;

  return (
    <div className={styles.gates}>
      <p className={styles.gateLine}>
        {quorum.met
          ? t('settlement.gates.quorumMet', { counted: quorum.counted, required: quorum.required })
          : t('settlement.gates.quorumShort', {
              counted: quorum.counted,
              required: quorum.required,
              missing: quorum.required - quorum.counted,
            })}
      </p>
      {quorum.debtorsStillToConfirm > 0 ? (
        <p className={styles.gateLine}>
          {t('settlement.gates.debtorsToConfirm', { count: quorum.debtorsStillToConfirm })}
        </p>
      ) : null}
      <p className={styles.gateLine}>
        {funding.satisfied ? t('settlement.gates.fundingSatisfied') : t('settlement.gates.fundingMissing')}
      </p>
      {/* What is still missing, per currency: a converted amount would be a different number in a currency nobody
          is settling in (ADR 0012), so the currencies are named and the amounts live on the run's own page. */}
      {funding.outstanding.length > 0 ? (
        <p className={styles.gateAmounts}>
          {funding.outstanding.map((amount) => amount.currency).join(', ')}
        </p>
      ) : null}
      {/* A verdict outlives its run, so the version it was reached against is mentioned when the two differ. */}
      {run.gates.status !== run.status ? (
        <p className={styles.gatesStale}>{t('settlement.gates.stale')}</p>
      ) : null}
    </div>
  );
}
