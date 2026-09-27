import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { sharedViewQuery } from '@/entities/invite';
import { useTranslations } from '@/shared/i18n';
import { toLocalInput } from '@/shared/lib/localTime';
import { Amount, Card, Notice, ProblemNotice } from '@/shared/ui';

import styles from './BalancesScreen.module.css';

/**
 * What a share link shows: the group's positions, to somebody who is not a member (ADR 0055).
 *
 * <p><strong>It is its own screen rather than a mode of the balances screen.</strong> The balances screen is a group's
 * own view - it resolves names the way a member does, offers a group's navigation, and lives under
 * {@code /groups/{groupId}}. This one is reached by a code, shows no navigation because the reader is not a member of
 * anything here, and knows nothing but what the link answered. What they share is the vocabulary: the direction words
 * come from the `netting` catalog, and the amounts are rendered by the same component with the same currency
 * exponents - so a reader of a link and a member of the group are not told different things about one number.
 *
 * <p><strong>It does not resolve names, because the server already did.</strong> A share holder cannot call
 * {@code GET /groups/{groupId}/members} - that read is the group's - so the position carries the name the group
 * recorded, and this screen falls back to the identifier exactly as the balances screen does for a row the group does
 * not name. The fallback is the same fact in both places: the currency clearing account is not a person.
 *
 * <p><strong>The stamp is shown rather than implied.</strong> A balance is "now", and this is the one read where the
 * reader's now and the server's may be days apart, so the screen says when the numbers were computed and when the link
 * stops working. A reader who needs fresher numbers asks the group for a new link; saying that is cheaper than letting
 * them assume the figure is live.
 */
export function SharedViewScreen({ code }: { readonly code: string }) {
  const { t } = useTranslations();

  const view = useQuery(sharedViewQuery(code));
  const currencies = useQuery(currencyCatalogQuery());

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);
  const positions = view.data?.positions ?? [];

  const group =
    view.data === undefined
      ? undefined
      : (view.data.group.name ?? view.data.group.id);

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('balances.shared.title')}</h1>
        <p className={styles.lede}>{t('balances.shared.lede')}</p>
      </header>

      {/* A catalogue failure is reported because an amount cannot be rendered without its exponent, and a wrong exponent
          is a wrong number rather than a missing one - the same reason the balances screen reports it. */}
      {currencies.isError ? <ProblemNotice fault={currencies.error} /> : null}

      {view.isError ? <ProblemNotice fault={view.error} /> : null}

      {view.isSuccess ? (
        <Card
          title={group ?? ''}
          description={t('balances.shared.description', { count: view.data.group.memberCount })}
        >
          <p className={styles.status}>
            {t('balances.shared.stamp', {
              generated: toLocalInput(view.data.generatedAt),
              expires: toLocalInput(view.data.expiresAt),
            })}
          </p>

          {positions.length === 0 ? (
            <Notice>{t('balances.shared.empty')}</Notice>
          ) : (
            <table className={styles.table}>
              <caption className={styles.caption}>{t('balances.shared.caption')}</caption>
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
                {positions.map((position) => (
                  <tr key={`${position.memberId}-${position.net.currency}`}>
                    {/* The identifier when the group did not name the row: the same fallback the balances screen makes,
                        and the footnote below says which two things it might be. */}
                    <th scope="row" className={styles.rowHeader}>
                      {position.displayName ?? position.memberId}
                    </th>
                    <td>
                      <span className={styles[position.direction]}>{t(`netting.direction.${position.direction}`)}</span>
                    </td>
                    <td className={styles.numeric}>
                      <Amount value={position.net} catalog={catalog} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {positions.some((position) => position.displayName === undefined) ? (
            <p className={styles.footnote}>{t('balances.list.unnamedNote')}</p>
          ) : null}
        </Card>
      ) : null}
    </div>
  );
}