import { createFileRoute, Link, Outlet } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';

import { groupQuery } from '@/entities/group';
import { useTranslations } from '@/shared/i18n';

import styles from './route.module.css';

/**
 * A group's routes, laid out together.
 *
 * The layout owns what every group screen would otherwise repeat: the group's identifier from the path, and the
 * navigation between the two views of one group - what is owed, and what a cycle would do with it. Both links
 * carry the current locale forward, because the language is part of the page's identity
 * (`docs/specs/i18n.md` section 3) and a navigation that dropped it would change the reader's language as a side
 * effect of changing the page.
 *
 * `aria-current="page"` on the active link is the one line of accessibility here that is not optional: a
 * navigation whose current item is only visually marked is a navigation that a screen reader announces as two
 * equal options.
 */
export const Route = createFileRoute('/groups/$groupId')({
  validateSearch: z.object({ lang: z.string().optional() }),
  component: GroupLayout,
});

/**
 * What this group is called, when the API knows.
 *
 * **The failure is silent, and it has to be.** `GET /groups/{groupId}` answers `404` for a group id that has commitments
 * but no `groups` row - a state this build's development data is in, because the table arrived additively rather than as a
 * foreign key every row had to satisfy (ADR 0043). A layout that rendered a problem notice for that would break screens
 * that work today, and would be telling a member their group does not exist while they are reading its commitments. So the
 * heading is the group's name when there is one, and the identifier when there is not - which is what this screen showed
 * before this slice.
 *
 * The `404` is also what a signed-out visitor gets, for the same reason it is what a stranger gets: this read answers one
 * code for "no such group" and "not a group you are in", so that nobody can use it to find out which ids are real.
 */
function GroupHeading() {
  const { groupId } = Route.useParams();
  const group = useQuery(groupQuery(groupId));

  return <h1 className={styles.heading}>{group.data?.name ?? groupId}</h1>;
}

function GroupLayout() {
  const { t } = useTranslations();
  const { groupId } = Route.useParams();

  // Only the language is carried across, so a filter from one view cannot arrive as an unknown parameter in
  // another.
  const carryLocale = (previous: { readonly lang?: string }) => ({ lang: previous.lang });

  return (
    <>
      <GroupHeading />

      <nav className={styles.nav} aria-label={t('common.groupNav.label')}>
        <Link
          className={styles.link}
          to="/groups/$groupId/members"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.members')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/obligations"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.obligations')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/balances"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.balances')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/activity"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.activity')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/netting"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.netting')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/schedule"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.schedule')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/settlement"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.settlement')}
        </Link>
        <Link
          className={styles.link}
          to="/groups/$groupId/tax"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.tax')}
        </Link>
      </nav>

      <Outlet />
    </>
  );
}
