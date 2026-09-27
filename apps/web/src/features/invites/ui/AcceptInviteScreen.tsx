import { useState } from 'react';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';

import { invitePreviewQuery } from '@/entities/invite';
import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { acceptanceOf, issueWith } from '../model/accept';
import { useAcceptInvite } from '../mutations';
import styles from './AcceptInviteScreen.module.css';

/**
 * The screen a code's link lands on: somebody joins a group.
 *
 * **It is outside the group's screens, and it has to be.** The person arriving is usually not a member yet - the group's
 * nav would be a list of screens they cannot read - and the code is the only thing the URL needs to carry. That is also
 * why the contract addresses this operation by the code and not by the group: the code says which group it is for.
 *
 * **It used to mint an identity, and now it takes one from the session.** This page generated the uuid that stood for
 * the person joining, because there was no sign-in to ask and the contract's description said so - and it promised that
 * "when sign-in exists, this body disappears and the acceptance becomes about the caller" (ADR 0047). Sign-in exists, so
 * the form asks for a name and the server joins whoever is signed in. The screen says that plainly, because a person
 * arriving from a link needs to know which account they are joining with rather than discovering it afterwards.
 *
 * **And since ADR 0054 it says what they are joining.** The code was spendable and not readable, so this page asked for
 * a name before it could name the group - which is the wrong order for somebody deciding whether to type it at all. The
 * preview is asked without a session (that is the operation's shape rather than a shortcut), and a code that has run out
 * is reported *instead of* the form: a stale link should tell somebody to ask for a new one, not collect their name and
 * then tell them.
 *
 * **It does not pretend the name is verified either.** The name is what the group will call them, and the copy says so:
 * the group is told who joined by the session, and nothing on the page can name somebody else.
 */
export function AcceptInviteScreen({ code }: { readonly code: string }) {
  const { t } = useTranslations();

  const preview = useQuery(invitePreviewQuery(code));
  const accept = useAcceptInvite(code);

  const [displayName, setDisplayName] = useState('');

  const issue = issueWith({ displayName });

  const submitted = acceptanceOf({ displayName });

  // The group's name when it has one, and the identifier when it does not: a group that exists only as rows is a state
  // this build contains (ADR 0043), and its codes are real.
  const group = preview.data?.group;

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('invites.title')}</h1>
        <p className={styles.lede}>{t('invites.lede')}</p>
      </header>

      <p className={styles.code} data-testid="invite-code">
        {code}
      </p>

      {/* A code that cannot be spent is the whole page: there is nothing to fill in, and the refusal is what the person
          needs to read. It is the same three codes the acceptance answers with, one step earlier. */}
      {preview.isError ? <ProblemNotice fault={preview.error} /> : null}

      {preview.isSuccess ? (
        <Card
          title={t('invites.preview.title')}
          description={
            group?.name === undefined
              ? t('invites.preview.withoutName', { groupId: group?.id ?? '' })
              : t('invites.preview.withName', { group: group.name })
          }
        >
          {group === undefined ? null : (
            <p className={styles.members}>{t('invites.preview.members', { count: group.memberCount })}</p>
          )}

          <p className={styles.caption}>
            {t('invites.preview.expires', { when: toLocalInput(preview.data.expiresAt) })}
          </p>
          {preview.data.maxUses === undefined ? <Notice>{t('invites.preview.noLimit')}</Notice> : null}
        </Card>
      ) : null}

      {accept.isSuccess ? (
        <Card title={t('invites.joined.title')} description={t('invites.joined.description')}>
          <p className={styles.joined}> {t('invites.joined.name', { name: accept.data.displayName })}</p>
          <p className={styles.identifier} data-testid="member-id">
            {accept.data.userId}
          </p>
          <Notice>{t('invites.joined.idNote')}</Notice>
          {/* The group id comes from the preview, which is the only reason this link can exist: the acceptance answers
              with a member, and a member carries no group. Before this read, somebody who had just joined had no way
              back to what they joined. */}
          {group === undefined ? null : (
            <Link to="/groups/$groupId/netting" params={{ groupId: group.id }}>
              {t('invites.joined.goToTheGroup')}
            </Link>
          )}
        </Card>
      ) : preview.isSuccess ? (
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
      ) : null}
    </div>
  );
}
