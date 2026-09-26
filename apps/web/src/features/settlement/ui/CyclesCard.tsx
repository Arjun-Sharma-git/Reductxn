import { useState, type FormEvent } from 'react';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';

import { groupCyclesQuery } from '@/entities/cycle';
import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { useOpenCycle } from '../mutations';
import { cycleState, openBody } from '../model/cycle';
import styles from './SettlementScreens.module.css';

/**
 * Closing the group's books at an instant, and what happened to every cycle it has closed before.
 *
 * <p><strong>The two halves of the same act.</strong> The form is ADR 0033's write - one instant, no policy, no
 * judgement - and the table is ADR 0034's read, which exists because without it the write was invisible: a cycle
 * waits for the worker's cadence, and a member watching a screen that showed only computed runs would see nothing
 * after asking. So the row says "asked <when>", and a plan appears on it when one exists.
 *
 * <p><strong>A form of one field, and the field is the whole request.</strong> The instant is required, because it
 * keys the cycle: a default of "now" would make two clicks two different settlements. The control is a
 * {@code datetime-local} - a wall clock in the reader's zone - and the conversion to an instant happens in
 * {@code shared/lib/localTime} rather than here, because getting it wrong records a time hours out and looks like a
 * data problem afterwards.
 *
 * <p><strong>The list is watched rather than polled forever.</strong> The one thing in this flow that is
 * asynchronous by design is the worker computing a plan, so while any cycle is waiting the page re-reads itself
 * every few seconds - and stops the moment none is. A screen that polled a settled list would be load without a
 * question; one that never polled would leave a member refreshing to see whether anything happened.
 *
 * <p><strong>Nothing here decides that a cycle is empty.</strong> The absence of a "nothing to settle" refusal is
 * the API's decision (ADR 0033) and this card keeps it: a commitment recorded tomorrow can carry an instant inside a
 * cycle opened today, so a form that refused an instant with nothing recorded at it would stop a member settling a
 * backdated bill. What the member sees instead, if the worker never plans it, is a row that says "asked" with a date
 * - which is a fact they can act on.
 */
export function CyclesCard({ groupId }: { readonly groupId: string }) {
  const { t, dateTime } = useTranslations();

  const [asOf, setAsOf] = useState(() => toLocalInput(new Date().toISOString()));
  const [refusal, setRefusal] = useState<string | undefined>();

  const cycles = useQuery({
    ...groupCyclesQuery(groupId),
    // A function rather than a number, so the interval follows the data: while something is waiting the page
    // re-reads, and once the worker has computed every plan it stops asking.
    refetchInterval: (query) =>
      (query.state.data ?? []).some((cycle) => cycleState(cycle) === 'waiting') ? 5_000 : false,
  });

  const open = useOpenCycle(groupId);
  const rows = cycles.data ?? [];
  const opened = open.data;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setRefusal(undefined);

    const body = openBody(asOf);

    if (!body.ok) {
      setRefusal(body.code);

      return;
    }

    open.mutate(body.value);
  }

  return (
    <Card title={t('settlement.cycles.cardTitle')} description={t('settlement.cycles.cardDescription')}>
      <form className={styles.cycleForm} onSubmit={submit}>
        <label htmlFor="cycle-as-of">{t('settlement.cycles.asOf')}</label>
        <input
          id="cycle-as-of"
          type="datetime-local"
          value={asOf}
          onChange={(event) => setAsOf(event.target.value)}
        />
        <p className={styles.hint}>{t('settlement.cycles.asOfHint')}</p>

        {refusal === undefined ? null : (
          <p className={styles.refusal} role="alert">
            {t('errors.' + refusal)}
          </p>
        )}

        <div className={styles.actions}>
          <button className="rn-button" type="submit" disabled={open.isPending}>
            {t('settlement.cycles.open')}
          </button>
        </div>
      </form>

      {open.isError ? <ProblemNotice fault={open.error} /> : null}

      {opened === undefined ? null : (
        <Notice>
          {opened.runId === undefined || opened.runId === null
            ? t('settlement.cycles.opened', { at: dateTime(opened.asOf) })
            : t('settlement.cycles.alreadyComputed', { at: dateTime(opened.asOf) })}
        </Notice>
      )}

      {cycles.isError ? <ProblemNotice fault={cycles.error} /> : null}

      {cycles.isSuccess && rows.length === 0 ? <Notice>{t('settlement.cycles.empty')}</Notice> : null}

      {rows.length > 0 ? (
        <table className={styles.table}>
          <caption className={styles.caption}>{t('settlement.cycles.caption')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('settlement.cycles.closedAt')}</th>
              <th scope="col">{t('settlement.cycles.askedAt')}</th>
              <th scope="col">{t('settlement.cycles.plan')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((cycle) => (
              <tr key={cycle.id}>
                <td>{dateTime(cycle.asOf)}</td>
                <td>{dateTime(cycle.createdAt)}</td>
                <td>
                  {cycle.runId === undefined || cycle.runId === null ? (
                    <span className={styles.waiting}>{t('settlement.cycles.waiting')}</span>
                  ) : (
                    /* The plan is a run, and the run's screen is where it is read - so this link is how a member
                       gets from "I asked for a settlement" to "here is what it says". */
                    <Link to="/groups/$groupId/settlement/$runId" params={{ groupId, runId: cycle.runId }}>
                      {t('settlement.cycles.openPlan')}
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </Card>
  );
}
