import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * The current reference rate for a pair, for a form that is about to record a cross-currency commitment.
 *
 * **What this is not.** It is not the rate the obligation will be locked at. The server locks a quote from
 * `fx_rates` at the obligation's own instant when the request arrives (`RecordObligationService`), and that rate
 * may differ from this one - so the screen says "indicative" and states the caveat rather than promising a number
 * the member can hold it to. What it is for is ADR 0012's principle that a currency choice should state its cost:
 * a member choosing to settle in another currency is choosing an FX conversion, and the screen should show what
 * that looks like before they do it rather than after.
 *
 * **Why a 503 is not retried here.** `GET /fx/rates` answers `503 fx.rate_unavailable` when the pair has no rate
 * at all, and the contract is explicit that this is an answer rather than a hiccup: "no rate means no
 * cross-currency". Retrying it would delay the only useful message the screen can give - that this pair cannot be
 * settled in yet - so this query opts out of the client's retry policy.
 */
export type FxRate = paths['/fx/rates']['get']['responses']['200']['content']['application/json'];

export const referenceRateQuery = (base: string, quote: string, enabled: boolean) =>
  queryOptions({
    queryKey: ['fx', 'rate', base, quote] as const,
    queryFn: () => request<FxRate>('/fx/rates', { query: { base, quote } }),
    enabled,
    retry: false,
  });
