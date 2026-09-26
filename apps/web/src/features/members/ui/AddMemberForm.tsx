import { useState, type FormEvent } from 'react';

import { useTranslations } from '@/shared/i18n';
import { Card, Field, Notice, ProblemNotice } from '@/shared/ui';

import { useAddMember } from '../mutations';
import { addMemberBody, emptyForm, localRefusals, refusalFor, refusalsOf, type MemberFormValues, type Refusal } from '../model/input';
import styles from './MembersScreen.module.css';

/**
 * Recording a member: a user id, a name, and a role.
 *
 * <p><strong>The field that wants an identifier is the honest part of this form.</strong> A build without a user
 * directory and without invites cannot offer a picker - there is nobody to look up, and a uuid is the only handle a
 * person has - so the form asks for one and says where to find it. The invite path is the next slice in this
 * direction, and until it lands this is the write an operator or a member uses to bring somebody in.
 *
 * <p><strong>One message, because the server's two answers mean one thing here.</strong> A request for somebody who
 * is already a member answers {@code 200} rather than {@code 201}, and the difference does not reach the screen: the
 * member is in the group either way, which is exactly the idempotence the pair's key gives (ADR 0035). Telling the
 * two apart would mean the form inspecting a status the client's request helper does not expose - and inventing a
 * difference the member does not care about is worse than saying the one true thing.
 */
export function AddMemberForm({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const [values, setValues] = useState<MemberFormValues>(emptyForm);
  const [local, setLocal] = useState<readonly Refusal[]>([]);

  const add = useAddMember(groupId);
  const server = add.isError ? refusalsOf(add.error) : [];
  const shown = local.length > 0 ? (server.length > 0 ? server : local) : server;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLocal([]);

    const body = addMemberBody(values);

    if (!body.ok) {
      // The same function the body used, so the field the message lands on and the reason the request was not sent
      // cannot disagree.
      setLocal(localRefusals(values));

      return;
    }

    add.mutate(body.value, {
      onSuccess: () => setValues(emptyForm()),
    });
  }

  return (
    <Card title={t('members.add.cardTitle')} description={t('members.add.cardDescription')}>
      <form className={styles.form} onSubmit={submit}>
        <Field
          id="member-user-id"
          label={t('members.add.userId')}
          hint={t('members.add.userIdHint')}
          refusal={refusalFor(shown, 'userId')}
        >
          <input
            id="member-user-id"
            value={values.userId}
            onChange={(event) => setValues({ ...values, userId: event.target.value })}
          />
        </Field>

        <Field
          id="member-name"
          label={t('members.add.displayName')}
          hint={t('members.add.displayNameHint')}
          refusal={refusalFor(shown, 'displayName')}
        >
          <input
            id="member-name"
            value={values.displayName}
            onChange={(event) => setValues({ ...values, displayName: event.target.value })}
          />
        </Field>

        <Field id="member-role" label={t('members.add.role')} hint={t('members.add.roleHint')}>
          <select
            id="member-role"
            value={values.role}
            onChange={(event) => setValues({ ...values, role: event.target.value === 'admin' ? 'admin' : 'member' })}
          >
            <option value="member">{t('members.role.member')}</option>
            <option value="admin">{t('members.role.admin')}</option>
          </select>
        </Field>

        {refusalFor(shown, 'form') === undefined ? null : (
          <p className={styles.refusal} role="alert">
            {t('errors.' + refusalFor(shown, 'form')!.code)}
          </p>
        )}

        <div className={styles.actions}>
          <button className="rn-button" type="submit" disabled={add.isPending}>
            {t('members.add.submit')}
          </button>
        </div>
      </form>

      {add.isError ? <ProblemNotice fault={add.error} /> : null}

      {add.isSuccess ? <Notice>{t('members.add.recorded', { name: add.data.displayName })}</Notice> : null}
    </Card>
  );
}
