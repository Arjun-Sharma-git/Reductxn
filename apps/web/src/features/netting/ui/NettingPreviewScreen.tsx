import { useMemo, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';

import { currencyCatalogQuery, indexByCode } from '@/entities/currency';
import { nettingPreviewQuery } from '@/entities/netting';
import { useTranslations } from '@/shared/i18n';
import { Notice, ProblemNotice } from '@/shared/ui';

import { useStaggerReveal } from '../animations';
import { PartitionsTable } from './PartitionsTable';
import { PositionsTable } from './PositionsTable';
import { TransfersTable } from './TransfersTable';
import styles from './NettingPreviewScreen.module.css';

/**
 * The group's netting preview: the first screen this client has, and the read-only half of the product.
 *
 * It is deliberately the first one. It needs no identity, no write path and no optimistic update, and it shows
 * the two facts the whole system exists to produce: what each member's position comes to, and what netting saves
 * over settling every commitment pair by pair. Everything the later screens need - the typed client, the currency
 * catalogue, locale-aware money, the feature slice - is exercised here.
 *
 * Three things in the markup are the skill's priority list rather than taste:
 *
 *   - **The counts are ICU plurals**, keyed on the number, because "1 members" makes a reader distrust the
 *     arithmetic next to it.
 *   - **Direction and status are words, never colours.** A row says `pay`, `receive` or `flat`, so it survives a
 *     monochrome screen, a colour-blind reader and a printout; the colour is reinforcement, not information.
 *   - **The figures are per currency and are never summed.** ADR 0012 says a total across currencies is
 *     meaningless without a reporting currency and a rate, so the screen says so rather than inventing one.
 */
export function NettingPreviewScreen({
  groupId,
  asOf,
}: {
  readonly groupId: string;
  readonly asOf?: string;
}) {
  const { t, fellBack, locale } = useTranslations();

  const preview = useQuery(nettingPreviewQuery(groupId, asOf));
  const currencies = useQuery(currencyCatalogQuery());

  const catalog = useMemo(() => indexByCode(currencies.data ?? []), [currencies.data]);

  const container = useRef<HTMLDivElement>(null);
  useStaggerReveal(container, preview.dataUpdatedAt);

  const partitions = preview.data?.partitions ?? [];
  const transfers = preview.data?.bestCase?.transfers ?? [];
  const positions = preview.data?.bestCase?.positions ?? [];
  const nothingToSettle = preview.isSuccess && partitions.length === 0;

  return (
    <div ref={container}>
      <header className={styles.header}>
        <h1>{t('netting.title')}</h1>
        <p className={styles.lede}>{t('netting.lede')}</p>
      </header>

      {/*
        The review-state rule, said out loud (`docs/specs/i18n.md` section 8.4): this screen is where a member
        reads what they will pay, so an unreviewed translation does not get to write it - and an unexplained
        fallback would look like a bug rather than a decision. It is the *screen* that says this and not the shell,
        because `requireReviewed` is a property of the screen: a settings page may show a machine translation, and
        a report that moves money may not.
      */}
      {fellBack ? (
        <Notice tone="warning">{t('common.unreviewedLocale', { locale })}</Notice>
      ) : null}

      {currencies.isError ? <ProblemNotice fault={currencies.error} /> : null}

      {preview.isPending ? (
        <p className={styles.status} role="status">
          {t('netting.loading')}
        </p>
      ) : null}

      {preview.isError ? <ProblemNotice fault={preview.error} /> : null}

      {nothingToSettle ? <Notice>{t('netting.empty')}</Notice> : null}

      {preview.isSuccess && !nothingToSettle ? (
        <>
          <PartitionsTable partitions={partitions} catalog={catalog} />
          <PositionsTable positions={positions} catalog={catalog} />
          <TransfersTable transfers={transfers} catalog={catalog} />
        </>
      ) : null}

      <p className={styles.asOf}>
        {asOf === undefined ? t('netting.asOf.now') : t('netting.asOf.at', { instant: asOf })}
      </p>
    </div>
  );
}
