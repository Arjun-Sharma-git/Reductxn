import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { membersQuery } from '@/entities/member';
import { groupBalancesQuery } from '@/entities/netting';
import { useTranslations } from '@/shared/i18n';
import { Amount, Card, Notice, ProblemNotice } from '@/shared/ui';

import { balanceRows, hasUnnamedRows } from '../model/rows';
import styles from './BalancesScreen.module.css';

/**
 * The group's balances: what each member owes or is owed right now.
 *
 * **This is the screen the members slice made possible.** Until names could be read, a position was a uuid; until the
 * balances endpoint existed, the only way to see positions was the netting preview, which answers with the whole plan
 * - partitions, transfers, tax - and shows identifiers rather than names on purpose (its table is about the plan
 * rather than about people). A member asking "what do I owe" was being shown a settlement they did not ask about
 * (ADR 0039).
 *
 * **The read is the preview's, so the two screens agree.** The server computes both with the same planner, and this
 * screen adds only what the server does not have: the group's names for the identifiers, and a heading that says what
 * the reader is looking at.
 *
 * **Three things are stated rather than implied.** The rows are per currency and are never summed (ADR 0012). The
 * direction is a word - `pay`, `receive`, `flat` - so it survives a monochrome screen and a screen reader. And a row
 * with no name says which of the two things it might be instead of guessing.
 *
 * The direction words come from the `netting` catalog rather than being written again here: it is one vocabulary for
 * one fact, and a second copy is how "pays" and "owes" start to drift apart across two screens.
 */
export function BalancesScreen({ groupId }: { readonly groupId: string }) {
  const { t } = useTranslations();

  const balances = useQuery(groupBalancesQuery(groupId));
  const members = useQuery(membersQuery(groupId));
  const currencies = useQuery(currencyCatalogQuery());

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);
  const rows = useMemo(
    () => balanceRows(balances.data ?? [], members.data ?? []),
    [balances.data, members.data],
  );

  const nothingOutstanding = balances.isSuccess && rows.length === 0;

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('balances.title')}</h1>
        <p className={styles.lede}>{t('balances.lede')}</p>
      </header>

      {/* The catalogue is reported because an amount cannot be rendered without its exponent, and a wrong exponent is
          a wrong number rather than a missing one. A member list that failed is not reported separately: the rows then
          show identifiers and the footnote below the table says so, which is the same thing the screen would say if
          the catalogue simply did not know the member. */}
      {currencies.isError ? <ProblemNotice fault={currencies.error} /> : null}

      {balances.isError ? <ProblemNotice fault={balances.error} /> : null}

      <Card title={t('balances.list.cardTitle')} description={t('balances.list.cardDescription')}>
        {balances.isPending ? (
          <p className={styles.status} role="status">
            {t('balances.loading')}
          </p>
        ) : null}

        {nothingOutstanding ? <Notice>{t('balances.empty')}</Notice> : null}

        {rows.length > 0 ? (
          <>
            <table className={styles.table}>
              <caption className={styles.caption}>{t('balances.list.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('balances.list.member')}</th>
                  <th scope="col">{t('balances.list.direction')}</th>
                  <th scope="col" className={styles.numeric}>
                    {t('balances.list.net')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.key}>
                    <th scope="row" className={styles.rowHeader}>
                      {row.name}
                    </th>
                    <td>
                      <span className={styles[row.direction]}>
                        {t(`netting.direction.${row.direction}`)}
                      </span>
                    </td>
                    <td className={styles.numeric}>
                      <Amount value={row.net} catalog={catalog} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {hasUnnamedRows(rows) ? (
              <p className={styles.footnote}>{t('balances.list.unnamedNote')}</p>
            ) : null}
          </>
        ) : null}
      </Card>
    </div>
  );
}
