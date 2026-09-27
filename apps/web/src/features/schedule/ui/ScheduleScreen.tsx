import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { groupScheduleQuery } from '@/entities/schedule';
import { problemOf } from '@/shared/api/client';
import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Card, Field, Notice, ProblemNotice } from '@/shared/ui';

import { useClearSchedule, useSetSchedule } from '../mutations';
import styles from './ScheduleScreen.module.css';

/**
 * When this group's books close, and how often after that (ADR 0057).
 *
 * <p><strong>This screen is the missing half of a cadence that already existed.</strong> The worker could open a cycle
 * from a schedule and compute a run for it; what nothing could do was *state* one - so every cycle in this product was
 * opened by somebody who remembered to open it. A feature only an API can reach is unreachable, which is the gap ADR
 * 0035 was written about.
 *
 * <p><strong>It says what the worker will do rather than only what the row holds.</strong> The two facts a member needs
 * are "when does this happen next" and "does it keep happening", and they are the row's own two fields - so the card puts
 * them in one sentence and the stamp is a caption rather than a headline.
 *
 * <p><strong>No schedule is a state, not an error.</strong> The read answers {@code 404 api.no_such_schedule} when a
 * group has never stated one, and that renders as a sentence - "this group's books close when a member asks" - with the
 * form beneath it. The same instinct the members screen has about a role nothing enforces: a screen that shows a fact
 * should say what the fact is worth, and "nothing closes your books by itself" is a fact a group may want to change.
 *
 * <p>The form takes a number of days and an instant, which is what the contract describes and no more: there is no
 * month-end or weekday choice in this build, and the footnote says so rather than leaving a member to wonder.
 */
export function ScheduleScreen({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const schedule = useQuery(groupScheduleQuery(groupId));
  const setSchedule = useSetSchedule(groupId);
  const clearSchedule = useClearSchedule(groupId);

  const [everyDays, setEveryDays] = useState('30');
  const [nextDueAt, setNextDueAt] = useState('');

  const refusal = setSchedule.isError ? setSchedule.error : clearSchedule.isError ? clearSchedule.error : null;
  const absent = schedule.isError && isNoSchedule(schedule.error);

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('schedule.title')}</h1>
        <p className={styles.lede}>{t('schedule.lede')}</p>
      </header>

      {/* A group with no cadence is not a fault, so the refusal is reported only when it is something else. */}
      {schedule.isError && !absent ? <ProblemNotice fault={schedule.error} /> : null}

      <Card title={t('schedule.now.cardTitle')} description={t('schedule.now.cardDescription')}>
        {schedule.isPending ? (
          <p className={styles.status} role="status">
            {t('schedule.loading')}
          </p>
        ) : null}

        {absent ? <Notice>{t('schedule.none')}</Notice> : null}

        {schedule.isSuccess ? (
          <>
            <p className={styles.headline} data-testid="schedule-headline">
              {t('schedule.now.closes', { everyDays: schedule.data.everyDays })}
            </p>
            <p className={styles.caption}>
              {t('schedule.now.next', { when: toLocalInput(schedule.data.nextDueAt) })}
            </p>
            <p className={styles.caption}>{t('schedule.now.set', { when: toLocalInput(schedule.data.setAt) })}</p>

            <button type="button" onClick={() => clearSchedule.mutate()} disabled={clearSchedule.isPending}>
              {clearSchedule.isPending ? t('schedule.stopping') : t('schedule.stop')}
            </button>
          </>
        ) : null}
      </Card>

      <Card title={t('schedule.statement.cardTitle')} description={t('schedule.statement.cardDescription')}>
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            setSchedule.mutate({
              everyDays: Number(everyDays),
              nextDueAt: new Date(nextDueAt).toISOString(),
            });
          }}
        >
          <Field id="schedule-every-days" label={t('schedule.statement.everyDays')} hint={t('schedule.statement.everyDaysHint')}>
            <input
              id="schedule-every-days"
              type="number"
              min={1}
              max={366}
              value={everyDays}
              onChange={(event) => setEveryDays(event.target.value)}
              required
            />
          </Field>

          {/* A `datetime-local`, because the contract's instant is the group's own: a member states "the 1st at nine" in
              the time their browser is in, and the server stores the instant that means. */}
          <Field id="schedule-next-due-at" label={t('schedule.statement.nextDueAt')} hint={t('schedule.statement.nextDueAtHint')}>
            <input
              id="schedule-next-due-at"
              type="datetime-local"
              value={nextDueAt}
              onChange={(event) => setNextDueAt(event.target.value)}
              required
            />
          </Field>

          <button type="submit" disabled={setSchedule.isPending}>
            {setSchedule.isPending ? t('schedule.statement.saving') : t('schedule.statement.save')}
          </button>
        </form>

        {refusal === null ? null : <ProblemNotice fault={refusal} />}

        {/* The one thing the mechanism does not do, said where a member is deciding it: an interval of days rather than
            a date in the month, and no way to say whose midnight. */}
        <p className={styles.footnote}>{t('schedule.statement.intervalNote')}</p>
      </Card>
    </div>
  );
}

/** True when a refusal is the absence a group with no cadence answers with, rather than a fault. */
function isNoSchedule(fault: unknown): boolean {
  return problemOf(fault).code === 'api.no_such_schedule';
}
