import { useState } from 'react';
import { Link, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { z } from 'zod';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { groupsQuery } from '@/entities/group';
import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { issueWith, nameFor } from '../model/group';
import { useCreateGroup } from '../mutations';
import styles from './GroupsScreen.module.css';

/**
 * The way in: the groups you are in, and one you can join with a code.
 *
 * **This screen replaced a placeholder, and the box that replaced it has changed what it takes.** Until ADR 0043 the only
 * way into a group was to paste its identifier, because there was nothing to list: `groups` did not exist as a table and
 * identity did not exist to say who was asking. The list is the front door now, and the box stayed for a reason that was
 * real - somebody handed an id by a person who forgot to invite them should be able to open it.
 *
 * **ADR 0054 removed that reason rather than the box.** A group's books are read by its members now, so pasting an id
 * leads to a `404`: the id was never a credential, and the paste box was the place the build pretended it was. What a
 * group hands out is a **code** - unguessable, expiring, and revocable by not making another one - so the box takes one
 * and goes to the invitation screen, which says which group it is for before anybody types their name.
 *
 * **A signed-out visitor is shown the way to sign in, not an error.** `GET /groups` answers `401` to a visitor, which is
 * the ordinary first visit - so the refusal renders as a note and a link, and the code box still works, because reading
 * what a code is for never needed to know who was asking either.
 */
export function GroupsScreen() {
  const { t } = useTranslations();
  const navigate = useNavigate();

  const groups = useQuery(groupsQuery());

  const [pasted, setPasted] = useState('');
  const [pastedInvalid, setPastedInvalid] = useState(false);

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('groups.title')}</h1>
        <p className={styles.lede}>{t('groups.lede')}</p>
      </header>

      {groups.isError ? (
        <Notice>
          {t('groups.signedOut')} <Link to="/sign-in">{t('common.signIn')}</Link>
        </Notice>
      ) : null}

      {groups.isSuccess ? (
        <Card title={t('groups.list.title')} description={t('groups.list.description')}>
          {groups.data.length === 0 ? (
            <p>{t('groups.list.empty')}</p>
          ) : (
            <ul className={styles.list}>
              {groups.data.map((group) => (
                <li key={group.id} className={styles.row}>
                  <Link to="/groups/$groupId/netting" params={{ groupId: group.id }} className={styles.name}>
                    {group.name}
                  </Link>
                  <span className={styles.meta}>
                    {t('groups.list.meta', { currency: group.currency, count: group.memberCount })}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      ) : null}

      {groups.isSuccess ? <CreateGroupCard /> : null}

      <Card title={t('groups.open.title')} description={t('groups.open.description')}>
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();

            // The client checks that something was typed and nothing more. The alphabet is the table's CHECK
            // constraint and the server decides what a code means, so a screen that validated the shape here would be a
            // second place that knows it - and would refuse a code the server would have accepted.
            const parsed = z.string().trim().min(1).safeParse(pasted);

            if (!parsed.success) {
              setPastedInvalid(true);

              return;
            }

            void navigate({ to: '/invites/$code', params: { code: parsed.data } });
          }}
        >
          <label className={styles.field}>
            {t('groups.open.code')}
            <input
              type="text"
              value={pasted}
              onChange={(event) => setPasted(event.target.value)}
              aria-invalid={pastedInvalid}
            />
          </label>

          <button type="submit">{t('groups.open.submit')}</button>
        </form>
      </Card>
    </div>
  );
}

/**
 * Creating one, in its own component so the screen above stays about *seeing* groups.
 *
 * The currency list is read here rather than passed down: this is the only place that needs it, and a prop threaded through
 * a screen to reach one form is a prop that outlives its reason.
 */
function CreateGroupCard() {
  const { t } = useTranslations();

  const catalogue = useQuery(currencyCatalogQuery());
  const createGroup = useCreateGroup();

  const [name, setName] = useState('');
  const [currency, setCurrency] = useState('');

  const currencies = indexByCode(catalogue.data ?? []);
  const issue = issueWith(name, currency);

  return (
    <Card title={t('groups.create.title')} description={t('groups.create.description')}>
      {createGroup.isError ? <ProblemNotice fault={createGroup.error} /> : null}

      <form
        className={styles.form}
        onSubmit={(event) => {
          event.preventDefault();

          if (issue !== null) {
            return;
          }

          createGroup.mutate(
            { name: nameFor(name), currency },
            {
              // The name is cleared but the currency is not: somebody making a second group for the same household is
              // making it in the same currency far more often than not.
              onSuccess: () => setName(''),
            },
          );
        }}
      >
        <label className={styles.field}>
          {t('groups.create.name')}
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={120}
          />
        </label>

        <label className={styles.field}>
          {t('groups.create.currency')}
          <select value={currency} onChange={(event) => setCurrency(event.target.value)}>
            <option value="">{t('groups.create.currencyPlaceholder')}</option>
            {[...currencies.keys()].map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </label>

        <button type="submit" disabled={createGroup.isPending || issue !== null}>
          {createGroup.isPending ? t('groups.create.busy') : t('groups.create.submit')}
        </button>
      </form>
    </Card>
  );
}
