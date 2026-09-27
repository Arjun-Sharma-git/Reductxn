import { useQuery } from '@tanstack/react-query';

import { membersQuery } from '@/entities/member';
import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { AddMemberForm } from './AddMemberForm';
import { InviteSomebodyCard } from './InviteSomebodyCard';
import { ShareLinkCard } from './ShareLinkCard';
import styles from './MembersScreen.module.css';

/**
 * The group's members: who can be named, and what the group calls them.
 *
 * <p><strong>This screen exists because every other screen was quietly broken without it.</strong> The member
 * catalogue has been read by the obligations list, its filters, the recording form and a run's answers since those
 * were built - and the endpoint behind it was never implemented, so in development each of those screens fell back to
 * showing a uuid where a name belongs, and the recording form could not pick a party at all. No test could see it:
 * the client's tests stub the API and the server had never asked for members. A screen is how that becomes visible,
 * and the members list is the one place the fallback would be invisible again (ADR 0035).
 *
 * <p><strong>The role is shown with what it does not do.</strong> It is recorded and nothing consults it - a build
 * without identity has no admin whose word a role would weigh more - so the caption says so rather than letting a
 * reader assume the word is enforced. The same instinct as the answer form's refusal, which named the guard for the
 * refused request: a screen that shows a fact should say what the fact is worth.
 */
export function MembersScreen({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const members = useQuery(membersQuery(groupId));
  const rows = members.data ?? [];

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('members.title')}</h1>
        <p className={styles.lede}>{t('members.lede')}</p>
      </header>

      {members.isError ? <ProblemNotice fault={members.error} /> : null}

      <AddMemberForm groupId={groupId} />

      <Card title={t('members.list.cardTitle')} description={t('members.list.cardDescription')}>
        {members.isPending ? (
          <p className={styles.status} role="status">
            {t('members.loading')}
          </p>
        ) : null}

        {members.isSuccess && rows.length === 0 ? <Notice>{t('members.list.empty')}</Notice> : null}

        {rows.length > 0 ? (
          <table className={styles.table}>
            <caption className={styles.caption}>{t('members.list.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('members.list.name')}</th>
                <th scope="col">{t('members.list.role')}</th>
                <th scope="col">{t('members.list.userId')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((member) => (
                <tr key={member.userId}>
                  <td>{member.displayName}</td>
                  <td>{t('members.role.' + member.role)}</td>
                  {/* The identifier is shown rather than hidden: it is what every other screen falls back to when a
                      name is missing, and a member who is asked for their user id needs to be able to read it. */}
                  <td className={styles.identifier}>{member.userId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Card>

      <InviteSomebodyCard groupId={groupId} />

      {/* The other thing a group hands out: a way to look, for somebody who is not going to join (ADR 0055). Shown only
          once the list has loaded, because making a link is a member's act and a signed-out visitor's request for one
          answers `401` - the same reason the invitation card sits beside the member list. */}
      {members.isSuccess ? <ShareLinkCard groupId={groupId} /> : null}
    </div>
  );
}
