import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * Obligations: the commitments a group has recorded, and the list the netting preview is computed from.
 *
 * The filters are the contract's own query parameters, and they are the reason this route exists in the plan as a
 * *filtered* list (`?status&member&from&to&sort`, MASTER-PLAN section 6): a ledger is read by narrowing, and every
 * narrowing has to be in the URL so a view can be sent to somebody else.
 *
 * The query key is built from the individual values rather than from the filter object. An object literal is a new
 * object on every render, so keying on it would mean a cache miss on every render - and a cache that misses is a
 * cache that is only a delay.
 */
export type Obligation = paths['/obligations']['post']['responses']['201']['content']['application/json'];

export type ObligationStatus = Obligation['status'];

/**
 * The two orders the endpoint accepts, read from the contract rather than spelled again.
 *
 * A free string here would let the route pass a sort the server refuses - which is exactly what it did before
 * `GET /obligations` existed to refuse it - and the contract declares the vocabulary, so this is a second look at
 * one fact rather than a second statement of it.
 */
export type ObligationSort = NonNullable<paths['/obligations']['get']['parameters']['query']>['sort'];

export type ObligationFilters = {
  readonly status?: ObligationStatus;
  readonly memberId?: string;
  readonly from?: string;
  readonly to?: string;
  readonly sort?: ObligationSort;
};

export const obligationsQuery = (groupId: string, filters: ObligationFilters = {}) =>
  queryOptions({
    queryKey: [
      'obligations',
      groupId,
      filters.status ?? null,
      filters.memberId ?? null,
      filters.from ?? null,
      filters.to ?? null,
      filters.sort ?? null,
    ] as const,
    queryFn: () => request<Obligation[]>('/obligations', { query: { groupId, ...filters } }),
  });
