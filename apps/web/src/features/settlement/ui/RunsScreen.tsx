import { useMemo } from 'react';
import { Link } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { groupRunsQuery } from '@/entities/run';
import { useTranslations } from '@/shared/i18n';
import { Amount, Card, Notice, ProblemNotice } from '@/shared/ui';

import { StatusBadge } from './parts';
import styles from './SettlementScreens.module.css';

/**
 * The group's settlement runs: what a member has to decide about.
 *
 * <p>This screen is the other half of the approval endpoint. Until the API could list a group's runs, a member could
 * answer a plan only if somebody had told them its identifier - the runs were addressable but not findable - so the
 * approval path existed and nobody could walk it. (The contract had `GET /runs/{runId}` and no `GET /runs`; ADR 0030
 * records adding it.)
 *
 * <p>The order is the server's (newest first, from the index the schema created for this read) and this screen does
 * not re-sort: a page that reordered what the server sent would make two readers disagree about a list.
 *
 * <p>What a row shows is what a member needs to choose: when it was computed, where it stands, what it moves per
 * currency, and how far off its gates are. The status is a phrase from `model/decision.ts` with a fallback for a
 * state the worker has learned and this screen has not - a reader that recognised only its own vocabulary would
 * print an identifier where a word belongs.
 */
export function RunsScreen({ groupId }: { readonly groupId: string }) {
  const { t, dateTime } = useTranslations();

  const runs = useQuery(groupRunsQuery(groupId));
  const currencies = useQuery(currencyCatalogQuery());

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);
  const rows = runs.data ?? [];

  return (
    <div className={styles.screen}>
      <header>
        <h1>{t('settlement.runs.title')}</h1>
        <p className={styles.lede}>{t('settlement.runs.lede')}</p>
      </header>

      {runs.isError ? <ProblemNotice fault={runs.error} /> : null}

      <Card title={t('settlement.runs.cardTitle')} description={t('settlement.runs.cardDescription')}>
        {runs.isPending ? (
          <p className={styles.status} role="status">
            {t('settlement.loading')}
          </p>
        ) : null}

        {runs.isSuccess && rows.length === 0 ? <Notice>{t('settlement.runs.empty')}</Notice> : null}

        {rows.length > 0 ? (
          <table className={styles.table}>
            <caption className={styles.caption}>{t('settlement.runs.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('settlement.runs.computedAt')}</th>
                <th scope="col">{t('settlement.runs.status')}</th>
                <th scope="col">{t('settlement.runs.moves')}</th>
                <th scope="col">{t('settlement.runs.gates')}</th>
                <th scope="col">{t('settlement.runs.open')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((run) => (
                <tr key={run.id} data-reveal>
                  <th scope="row" className={styles.rowHeader}>
                    {run.computedAt ? dateTime(run.computedAt) : null}
                  </th>
                  <td>
                    <StatusBadge status={run.status} />
                  </td>
                    <td>
                      {/* Per currency, one line each: no total, because nothing adds rupees to yen. */}
                      {(run.partitions ?? []).map((partition) => (
                        <p key={partition.currency} className={styles.moves}>
                          <Amount value={partition.net} catalog={catalog} />
                        </p>
                      ))}
                    </td>
                    <td>
                      {run.gates === undefined || run.gates === null
                        ? t('settlement.runs.notLookedYet')
                        : t('settlement.runs.quorumSoFar', {
                            counted: run.gates.quorum.counted,
                            required: run.gates.quorum.required,
                          })}
                    </td>
                    <td>
                      <Link
                        to="/groups/$groupId/settlement/$runId"
                        params={{ groupId, runId: run.id }}
                        search={(previous) => ({ lang: previous.lang })}
                      >
                        {t('settlement.runs.open')}
                      </Link>
                    </td>
                  </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </Card>
    </div>
  );
}
