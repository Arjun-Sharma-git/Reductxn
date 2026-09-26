import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * The currency catalogue: a code as the exponent needed to render an amount in it.
 *
 * The contract is explicit about why this exists (`/currencies`): "a client needs the exponent to render a Money
 * correctly (P1). The server deliberately does not repeat the exponent on every amount, because it is reference
 * data." So the client fetches it once and indexes it - and every amount on every screen resolves its exponent
 * here rather than assuming two.
 *
 * `staleTime` is an hour rather than zero: a currency's minor unit is not a thing that changes during a
 * session, and the contract asks for aggressive caching. The query is still a query, so a reload after a
 * deployment picks up a catalogue that did change.
 */
export type CurrencyInfo =
  paths['/currencies']['get']['responses']['200']['content']['application/json'][number];

export const currencyCatalogQuery = () =>
  queryOptions({
    queryKey: ['reference', 'currencies'] as const,
    queryFn: () => request<CurrencyInfo[]>('/currencies'),
    staleTime: 60 * 60 * 1000,
  });

/** The catalogue as a lookup, which is what a component actually needs. */
export function indexByCode(list: readonly CurrencyInfo[]): ReadonlyMap<string, CurrencyInfo> {
  return new Map(list.map((info) => [info.code, info]));
}
