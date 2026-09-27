import { useQuery } from '@tanstack/react-query';

import { activityQuery, type ActivityEvent } from '@/entities/activity';
import { useTranslations, type Translations } from '@/shared/i18n';
import { Card, ProblemNotice } from '@/shared/ui';

import styles from './ActivityScreen.module.css';

/**
 * What has happened in this group.
 *
 * **Three kinds, rendered by a switch over the contract's enum**, so a kind the schema gains is a build failure here rather
 * than a row that renders as nothing. That is the whole reason the server sends structured fields instead of a sentence: the
 * wording, the language and the order of clauses are this screen's, not the API's (RULE B20, ADR 0014).
 *
 * **Two rows deliberately do not name anybody, and the screen says so rather than inventing one.** An invitation event has
 * no member because the create is unauthenticated in this build and the row names nobody; a decision event has a member id
 * but no display name, because `settlement_decisions` records who answered and not what the group calls them. So the feed
 * reports "a member answered" rather than a name it does not have (ADR 0044) - a screen that showed a uuid would be worse,
 * and one that guessed would be wrong.
 *
 * The instants are rendered through the locale's own formatter, in the reader's zone: which cycle an obligation falls in is
 * the group's timezone's business, and when something happened to *you* is yours (`docs/specs/i18n.md` section 7).
 */
export function ActivityScreen({ groupId }: { readonly groupId: string }) {
  const { t, dateTime } = useTranslations();
  const activity = useQuery(activityQuery(groupId));

  return (
    <Card title={t('activity.title')} description={t('activity.description')}>
      {activity.isError ? <ProblemNotice fault={activity.error} /> : null}

      {activity.isSuccess && activity.data.length === 0 ? <p>{t('activity.empty')}</p> : null}

      {activity.isSuccess && activity.data.length > 0 ? (
        <ol className={styles.feed}>
          {activity.data.map((event, index) => (
            <li key={`${event.kind}-${event.at}-${index}`} className={styles.event}>
              <span className={styles.at}>{dateTime(event.at)}</span>
              <span className={styles.what}>{sentence(event, t)}</span>
            </li>
          ))}
        </ol>
      ) : null}
    </Card>
  );
}

/** One event as a sentence, in the reader's language, from the fields the row actually has. */
function sentence(event: ActivityEvent, t: Translations['t']): string {
  switch (event.kind) {
    case 'member_joined':
      return t('activity.memberJoined', {
        name: event.displayName ?? t('activity.somebody'),
        role: t(`activity.roles.${event.role === 'admin' ? 'admin' : 'member'}`),
      });

    case 'decision_recorded':
      return t('activity.decisionRecorded', {
        decision: t(`activity.decisions.${event.decision === 'approve' ? 'approve' : 'reject'}`),
      });

    case 'invitation_created':
      return event.maxUses === undefined
        ? t('activity.invitationCreatedUnbounded', { uses: event.uses ?? 0 })
        : t('activity.invitationCreated', { uses: event.uses ?? 0, max: event.maxUses });
  }
}
