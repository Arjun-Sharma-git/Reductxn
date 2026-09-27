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
 * The way in: the groups you are in, and one you can paste an id for.
 *
 * **This screen replaced a placeholder, and the placeholder has not entirely gone.** Until this slice the only way into a
 * group was to paste its identifier, because there was nothing to list: `groups` did not exist as a table and identity did
 * not exist to say who was asking. Both are true now, so the list is the front door - and the paste box stays, for two
 * reasons that are real rather than sentimental. Development data has groups with commitments but no group row
 * (ADR 0043), so a group can exist in this build without appearing in anybody's list; and somebody who was handed an id by
 * a person who forgot to invite them should be able to open it rather than be told to ask again.
 *
 * **A signed-out visitor is shown the way to sign in, not an error.** `GET /groups` answers `401` to a visitor, which is
 * the ordinary first visit - so the refusal renders as a note and a link, and the paste box still works, because opening a
 * group by id never needed to know who was asking.
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

            const parsed = z.string().uuid().safeParse(pasted.trim());

            if (!parsed.success) {
              setPastedInvalid(true);

              return;
            }

            void navigate({ to: '/groups/$groupId/netting', params: { groupId: parsed.data } });
          }}
        >
          <label className={styles.field}>
            {t('groups.open.groupId')}
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
