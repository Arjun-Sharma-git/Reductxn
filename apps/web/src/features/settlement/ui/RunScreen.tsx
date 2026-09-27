import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { membersQuery } from '@/entities/member';
import { runQuery, runTransfersQuery } from '@/entities/run';
import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { alreadyAnswered } from '../model/decision';
import { useDecide, type DecisionWord } from '../mutations';
import { AnswerRows, Gates, PartitionRows, StatusBadge, TransferRows } from './parts';
import styles from './SettlementScreens.module.css';

/**
 * One run: what the plan asks for, what it has concluded, who has answered, and the answer.
 *
 * <p>This is the screen the product's approval path was missing entirely. The endpoint that records a member's answer
 * has existed since ADR 0020 and the report since ADR 0021; what did not exist was anywhere a member could read the
 * plan and say yes or no. It is also where the two distinctions the contract is careful about become visible rather
 * than theoretical:
 *
 *   - **An answer is recorded, and its effect is the run's.** The screen shows the answer on the record and the run's
 *     *status* beside it, and it never says "approved": whether an answer counted is RULE S1's arithmetic in the
 *     worker, and the API refuses to answer it (ADR 0020, 0021). Nothing is written optimistically, for the same
 *     reason - a provisional answer in the cache would be this screen claiming an impact.
 *   - **A refusal is an answer, not a failure.** The two buttons are equal in weight for the run's purposes, and the
 *     screen does not warn that refusing ends the run: that is the run's own status change, and stating it here would
 *     be predicting arithmetic this screen does not have.
 *
 * <p><strong>The member is not chosen any more: the session is who is answering.</strong> This operation used to name
 * its acting member in the body, and the screen had a picker for it - so a member could answer, or claim to answer,
 * as anybody the group listed. Since ADR 0053 the caller is the answering member, the field is gone from the contract,
 * and the picker is gone with it: there is nothing left to choose, which is a shorter form rather than a lost feature.
 * This paragraph and that picker were the last two places in this screen that described the old boundary.
 */
/**
 * The answer form: which way, and nothing about who.
 *
 * <p>Nothing about the form is a rule, and that is a change worth noticing: the buttons used to be disabled until a
 * member was chosen, because the screen had to supply the answer's author and the server refused an answer by nobody.
 * The session supplies the author now (ADR 0053), so a member who is signed in can answer with one click and one who
 * is not gets the refusal the API sends rather than a form that will not submit.
 *
 * <p>The recorded message repeats the server's word for the answer rather than the button's: the response echoes what
 * the row holds (`DecisionResponse`), which is what tells a member that a rejection was recorded as a rejection.
 */
export function Decision({
  answered,
  pending,
  recorded,
  fault,
  onAnswer,
}: {
  readonly answered: boolean;
  readonly pending: boolean;
  readonly recorded: string | undefined;
  readonly fault: unknown;
  readonly onAnswer: (decision: DecisionWord) => void;
}) {
  const { t } = useTranslations();

  return (
    <Card title={t('settlement.decision.title')} description={t('settlement.decision.description')}>
      <div className={styles.decision}>
        {answered ? null : (
          <div className={styles.actions}>
            <button
              className="rn-button"
              type="button"
              disabled={pending}
              onClick={() => onAnswer('approve')}
            >
              {t('settlement.decision.approve')}
            </button>
            <button
              className={`rn-button ${styles.reject}`}
              type="button"
              disabled={pending}
              onClick={() => onAnswer('reject')}
            >
              {t('settlement.decision.reject')}
            </button>
          </div>
        )}
      </div>

      {pending ? (
        <p className={styles.status} role="status">
          {t('settlement.decision.recording')}
        </p>
      ) : null}

      {recorded === undefined ? null : (
        <p className={styles.status} role="status">
          {t('settlement.decision.recorded', { decision: t(`settlement.answer.${recorded}`) })}
        </p>
      )}

      {fault === undefined || fault === null ? null : <ProblemNotice fault={fault} />}
      {answered ? <p className={styles.status}>{t('settlement.decision.alreadyAnswered')}</p> : null}
    </Card>
  );
}

export function RunScreen({ groupId, runId }: { readonly groupId: string; readonly runId: string }) {
  const { t, dateTime } = useTranslations();

  const run = useQuery(runQuery(runId));
  const transfers = useQuery(runTransfersQuery(runId));
  const members = useQuery(membersQuery(groupId));
  const currencies = useQuery(currencyCatalogQuery());

  const decide = useDecide(groupId, runId);

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);

  // Once the server says this member has answered, the buttons go away: the answer is never overwritten, so offering
  // them again would be inviting something the system will not do.
  const answered = decide.isError && alreadyAnswered(decide.error);
  const answer = (decision: DecisionWord) => decide.mutate({ decision });

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('settlement.run.title')}</h1>
        {run.data === undefined ? null : (
          <p className={styles.lede}>
            <StatusBadge status={run.data.status} />{' '}
            {run.data.computedAt
              ? t('settlement.run.computedAt', { at: dateTime(run.data.computedAt) })
              : null}
          </p>
        )}
      </header>

      {run.isError ? <ProblemNotice fault={run.error} /> : null}
      {run.isPending ? (
        <p className={styles.status} role="status">
          {t('settlement.loading')}
        </p>
      ) : null}

      {run.data === undefined ? null : (
        <>
          <Gates run={run.data} />

          <Card title={t('settlement.partitions.title')} description={t('settlement.partitions.description')}>
            <PartitionRows run={run.data} catalog={catalog} />
          </Card>

          <Card title={t('settlement.transfers.title')} description={t('settlement.transfers.description')}>
            {transfers.isError ? <ProblemNotice fault={transfers.error} /> : null}
            {transfers.isPending ? (
              <p className={styles.status} role="status">
                {t('settlement.loading')}
              </p>
            ) : null}
            {transfers.isSuccess && transfers.data.length === 0 ? (
              <Notice>{t('settlement.transfers.empty')}</Notice>
            ) : null}
            {transfers.isSuccess && transfers.data.length > 0 ? (
              <TransferRows transfers={transfers.data} members={members.data ?? []} catalog={catalog} />
            ) : null}
          </Card>

          <Card title={t('settlement.answers.title')} description={t('settlement.answers.description')}>
            {(run.data.answers ?? []).length === 0 ? (
              <Notice>{t('settlement.answers.empty')}</Notice>
            ) : (
              <AnswerRows run={run.data} members={members.data ?? []} />
            )}
          </Card>

          <Decision
            answered={answered}
            pending={decide.isPending}
            recorded={decide.isSuccess ? decide.data.decision : undefined}
            fault={decide.error}
            onAnswer={answer}
          />
        </>
      )}
    </div>
  );
}
