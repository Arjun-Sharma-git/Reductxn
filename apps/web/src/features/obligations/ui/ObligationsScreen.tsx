import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { membersQuery } from '@/entities/member';
import { obligationsQuery, type ObligationFilters, type ObligationStatus } from '@/entities/obligation';
import { useTranslations } from '@/shared/i18n';
import { Card, Notice, ProblemNotice } from '@/shared/ui';

import { RecordObligationForm } from './RecordObligationForm';
import { ObligationRows } from './ObligationRows';
import styles from './ObligationsScreen.module.css';

/**
 * The group's commitments: what has been recorded, filtered, and the form that records one.
 *
 * The list is the reason this route exists in the plan as a *filtered* list (`?status&member&from&to&sort`). Every
 * filter is a search parameter rather than component state, so a narrowed view is a link somebody can send - and
 * the route (not this file) is what parses them, which is RULE F4's division of labour: this component receives the
 * filters and reports a change, and never touches the URL.
 *
 * The recording form lives on the same screen rather than on its own route. A member records a commitment to see it
 * appear; splitting the two would put a navigation between the action and its result, and the optimistic row would
 * be inserted into a list nobody was looking at. A separate `/obligations/new` route is cheap to add later if a
 * mobile flow wants one.
 *
 * The status words come from the catalog and are used by both the filter and the rows, so one place decides what
 * `netted` is called in each language.
 */
export function ObligationsScreen({
  groupId,
  filters,
  onFiltersChange,
}: {
  readonly groupId: string;
  readonly filters: ObligationFilters;
  readonly onFiltersChange: (next: Partial<ObligationFilters>) => void;
}) {
  const { t } = useTranslations();

  const obligations = useQuery(obligationsQuery(groupId, filters));
  const currencies = useQuery(currencyCatalogQuery());
  const members = useQuery(membersQuery(groupId));

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);
  const rows = obligations.data ?? [];

  const change = (next: Partial<ObligationFilters>) => onFiltersChange(next);

  return (
    <div className={styles.screen}>
      <header className={styles.header}>
        <h1>{t('obligations.title')}</h1>
        <p className={styles.lede}>{t('obligations.lede')}</p>
      </header>

      <div className={styles.filters} role="group" aria-label={t('obligations.filters.label')}>
        <label className={styles.filter}>
          <span className={styles.filterLabel}>{t('obligations.filters.status')}</span>
          <select
            className={styles.filterControl}
            value={filters.status ?? ''}
            onChange={(event) =>
              change({
                status: event.target.value === '' ? undefined : (event.target.value as ObligationStatus),
              })
            }
          >
            <option value="">{t('obligations.filters.anyStatus')}</option>
            <option value="open">{t('obligations.status.open')}</option>
            <option value="netted">{t('obligations.status.netted')}</option>
            <option value="settled">{t('obligations.status.settled')}</option>
            <option value="voided">{t('obligations.status.voided')}</option>
          </select>
        </label>

        <label className={styles.filter}>
          <span className={styles.filterLabel}>{t('obligations.filters.member')}</span>
          <select
            className={styles.filterControl}
            value={filters.memberId ?? ''}
            onChange={(event) =>
              change({ memberId: event.target.value === '' ? undefined : event.target.value })
            }
          >
            <option value="">{t('obligations.filters.anyMember')}</option>
            {(members.data ?? []).map((member) => (
              <option key={member.userId} value={member.userId}>
                {member.displayName}
              </option>
            ))}
          </select>
        </label>

        <label className={styles.filter}>
          <span className={styles.filterLabel}>{t('obligations.filters.from')}</span>
          <input
            className={styles.filterControl}
            type="date"
            value={filters.from ?? ''}
            onChange={(event) =>
              change({ from: event.target.value === '' ? undefined : event.target.value })
            }
          />
        </label>

        <label className={styles.filter}>
          <span className={styles.filterLabel}>{t('obligations.filters.to')}</span>
          <input
            className={styles.filterControl}
            type="date"
            value={filters.to ?? ''}
            onChange={(event) =>
              change({ to: event.target.value === '' ? undefined : event.target.value })
            }
          />
        </label>
      </div>

      {obligations.isError ? <ProblemNotice fault={obligations.error} /> : null}

      <Card title={t('obligations.list.title')} description={t('obligations.list.description')}>
        {obligations.isPending ? (
          <p className={styles.status} role="status">
            {t('obligations.loading')}
          </p>
        ) : null}

        {obligations.isSuccess && rows.length === 0 ? <Notice>{t('obligations.empty')}</Notice> : null}

        {rows.length > 0 ? (
          <ObligationRows
            groupId={groupId}
            rows={rows}
            members={members.data ?? []}
            catalog={catalog}
          />
        ) : null}
      </Card>

      <RecordObligationForm groupId={groupId} filters={filters} catalog={catalog} />
    </div>
  );
}
