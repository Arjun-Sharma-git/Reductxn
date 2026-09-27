import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { useCreateShare } from '../shareMutation';
import styles from './InviteSomebodyCard.module.css';

/**
 * The card that makes a link somebody can look through without joining.
 *
 * <p><strong>It belongs beside the invitation card because it is the same act with a different audience.</strong> A
 * group hands out two things: a way in, for somebody who is about to become a member, and a way to look, for somebody
 * who never will - an accountant, a landlord, a mediator. Showing the second only in an API would leave the feature
 * unreachable from the product, which is the gap ADR 0035 was written about.
 *
 * <p><strong>It says what the link shows and what it does not.</strong> The caption names the positions and the member
 * count, and the notice says plainly that whoever holds the link can read them until it expires - because a link like
 * this is forwarded, and the person deciding to make one is deciding about the group's numbers rather than their own.
 * Nothing about memos or the group's history is disclosed, and the card says that too: a reader who does not know what a
 * share link shows cannot judge whether to make one.
 */
export function ShareLinkCard({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const create = useCreateShare(groupId);

  return (
    <Card title={t('members.share.cardTitle')} description={t('members.share.cardDescription')}>
      <button type="button" onClick={() => create.mutate()} disabled={create.isPending}>
        {create.isPending ? t('members.share.making') : t('members.share.make')}
      </button>

      {create.isError ? <ProblemNotice fault={create.error} /> : null}

      {create.isSuccess ? (
        <>
          <p className={styles.code} data-testid="share-code">
            {create.data.code}
          </p>
          <p className={styles.caption}>{t('members.share.expires', { when: toLocalInput(create.data.expiresAt) })}</p>
          {/* The warning is not decoration: a link is a bearer credential, and the person making one is the only person
              who can decide whether the group's numbers may travel with it. */}
          <Notice tone="warning">{t('members.share.warning')}</Notice>
        </>
      ) : null}
    </Card>
  );
}