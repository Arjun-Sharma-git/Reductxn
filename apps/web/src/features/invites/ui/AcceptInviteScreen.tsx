import { useMemo, useState } from 'react';

import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { acceptanceOf, issueWith, newMemberId } from '../model/accept';
import { useAcceptInvite } from '../mutations';
import styles from './AcceptInviteScreen.module.css';

/**
 * The screen a code's link lands on: somebody joins a group.
 *
 * **It is outside the group's screens, and it has to be.** The person arriving is usually not a member yet - the group's
 * nav would be a list of screens they cannot read - and the code is the only thing the URL needs to carry. That is also
 * why the contract addresses this operation by the code and not by the group: the code says which group it is for, and
 * the screen would have nothing else to show.
 *
 * **It says who it is about to become.** This build has no sign-in, so the page mints the id that stands for the person
 * joining and shows it after they are in (ADR 0041). A screen that quietly invented an identity would be worse than one
 * that says so: the id is what every other screen in this product will show next to their name, and somebody who never
 * saw it would be looking at a stranger's row.
 *
 * **It does not pretend the name is verified either.** The name is what the group will call them, and the contract is
 * explicit that the body is an assertion rather than a fact - so the card says the group will use the name they type
 * rather than claiming anybody checked it.
 */
export function AcceptInviteScreen({ code }: { readonly code: string }) {
  const { t } = useTranslations();

  const accept = useAcceptInvite(code);

  // Minted once per visit rather than per keystroke: the id is this person's identity for the join, so a re-render must
  // not replace it - a second id would be a second member.
  const [memberId] = useState(newMemberId);
  const [displayName, setDisplayName] = useState('');

  const issue = issueWith({ displayName });

  const submitted = useMemo(
    () => acceptanceOf({ displayName }, memberId),
    [displayName, memberId],
  );

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('invites.title')}</h1>
        <p className={styles.lede}>{t('invites.lede')}</p>
      </header>

      <p className={styles.code} data-testid="invite-code">
        {code}
      </p>

      {accept.isSuccess ? (
        <Card title={t('invites.joined.title')} description={t('invites.joined.description')}>
          <p className={styles.joined}> {t('invites.joined.name', { name: accept.data.displayName })}</p>
          <p className={styles.identifier} data-testid="member-id">
            {accept.data.userId}
          </p>
          <Notice>{t('invites.joined.idNote')}</Notice>
        </Card>
      ) : (
        <Card title={t('invites.form.title')} description={t('invites.form.description')}>
          <Notice>{t('invites.form.identity')}</Notice>

          {accept.isError ? <ProblemNotice fault={accept.error} /> : null}

          <form
            className={styles.form}
            onSubmit={(event) => {
              event.preventDefault();

              if (issue === null) {
                accept.mutate(submitted);
              }
            }}
          >
            <label className={styles.field}>
              {t('invites.form.name')}
              <input
                type="text"
                value={displayName}
                maxLength={120}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            {/* Disabled while there is nothing to send: the name is the only thing the form asks for, and a blank one is
                a member the group cannot name. The server refuses it too, so this saves a request rather than enforcing
                a rule. */}
            <button type="submit" disabled={accept.isPending || issue !== null}>
              {accept.isPending ? t('invites.form.joining') : t('invites.form.join')}
            </button>
          </form>
        </Card>
      )}
    </div>
  );
}
