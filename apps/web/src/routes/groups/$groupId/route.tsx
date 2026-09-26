import { createFileRoute, Link, Outlet } from '@tanstack/react-router';
import { z } from 'zod';

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

function GroupLayout() {
  const { t } = useTranslations();
  const { groupId } = Route.useParams();

  // Only the language is carried across, so a filter from one view cannot arrive as an unknown parameter in
  // another.
  const carryLocale = (previous: { readonly lang?: string }) => ({ lang: previous.lang });

  return (
    <>
      <nav className={styles.nav} aria-label={t('common.groupNav.label')}>
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
          to="/groups/$groupId/netting"
          params={{ groupId }}
          search={carryLocale}
          activeProps={{ 'aria-current': 'page', className: styles.current }}
        >
          {t('common.groupNav.netting')}
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
      </nav>

      <Outlet />
    </>
  );
}
