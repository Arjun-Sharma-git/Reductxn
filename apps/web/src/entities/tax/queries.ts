import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Tax, both halves: the rules in force and what a group's plans have assessed.
 *
 * They live in one entity because they are one subject and one screen, and because the report and the rules answer
 * questions about each other - "what are we charged under" and "what did it come to". The rules are the deployment's
 * reference data rather than a group's, which is why that query takes no group.
 */

/** A rule as configured: a rate for a currency over an effective window, with the statute it encodes. */
export type TaxRule = paths['/tax/rules']['get']['responses']['200']['content']['application/json'][number];

/** One currency's assessed tax for a period, with the saving netting made against it. */
export type TaxReportLine =
  paths['/tax/reports']['get']['responses']['200']['content']['application/json'][number];

/**
 * The rules in force, for this deployment's jurisdiction.
 *
 * Cached like other reference data: a rule changes when a statute changes, not between two clicks. The server decides
 * "in force" at the instant it answers, so the key carries no instant - there is no past rule set to ask for
 * (ADR 0040), and a key that invented one would invite a cache to keep two answers to one question.
 */
export const taxRulesQuery = () =>
  queryOptions({
    queryKey: ['tax', 'rules'] as const,
    queryFn: () => request<TaxRule[]>('/tax/rules'),
    staleTime: 5 * 60 * 1000,
  });

/**
 * What the group's plans have assessed, per currency, over a period.
 *
 * The period is part of the *question*, so it is part of the key: an empty range is a different answer from all time,
 * and a cache that ignored the difference would show one period's tax under another's heading - the same argument the
 * netting preview makes for `asOf`. `'all'` stands in for an absent bound so the key stays a stable tuple.
 */
export const taxReportQuery = (groupId: string, periodStart?: string, periodEnd?: string) =>
  queryOptions({
    queryKey: ['tax', 'report', groupId, periodStart ?? 'all', periodEnd ?? 'all'] as const,
    queryFn: () =>
      request<TaxReportLine[]>('/tax/reports', {
        query: { groupId, periodStart, periodEnd },
      }),
  });
