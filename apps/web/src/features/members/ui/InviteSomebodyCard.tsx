import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { useCreateInvite } from '../inviteMutation';
import styles from './InviteSomebodyCard.module.css';

/**
 * The card that makes a code, on the members screen.
 *
 * It belongs on the members screen rather than on a screen of its own, because inviting somebody is how a member
 * arrives: the group's list of people and the way the next person gets onto it are one thing to look at. The acceptance
 * half is a different screen - the person accepting is not looking at the group's member list, and usually will not
 * even be a member yet (ADR 0041).
 *
 * **It says what it is handing out.** A code lasts seven days and has no limit, and it lets whoever holds it join - so
 * the card says all three rather than showing a string and leaving the reader to guess how careful to be with it. The
 * values come from the operation's description because the operation has no body: this is what the server does, not what
 * the caller asked for, and that is the one thing a reader needs to know about an invitation they are about to send.
 */
export function InviteSomebodyCard({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const create = useCreateInvite(groupId);

  return (
    <Card title={t('members.invite.cardTitle')} description={t('members.invite.cardDescription')}>
      <button type="button" onClick={() => create.mutate()} disabled={create.isPending}>
        {create.isPending ? t('members.invite.making') : t('members.invite.make')}
      </button>

      {create.isError ? <ProblemNotice fault={create.error} /> : null}

      {create.isSuccess ? (
        <>
          {/* Selectable in one gesture, and monospaced: the code is meant to be copied, and it is the one thing on this
              screen that a member has to transcribe exactly. */}
          <p className={styles.code} data-testid="invite-code">
            {create.data.code}
          </p>
          <p className={styles.caption}>{t('members.invite.expires', { when: toLocalInput(create.data.expiresAt) })}</p>
          {create.data.maxUses === undefined ? (
            <Notice tone="warning">{t('members.invite.noLimit')}</Notice>
          ) : null}
        </>
      ) : null}
    </Card>
  );
}
