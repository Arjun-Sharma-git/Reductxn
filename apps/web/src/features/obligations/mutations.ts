import { useMutation, useQueryClient } from '@tanstack/react-query';

import { obligationsQuery, type Obligation, type ObligationFilters } from '@/entities/obligation';
import { newIdempotencyKey, request } from '@/shared/api/client';
import type { Money } from '@/shared/money';

import { worthRetrying, type RecordFormValues } from './model/input';

/**
 * Recording an obligation: the client's first write path.
 *
 * Four decisions here are the whole of it, and each is a rule from somewhere else in the plan.
 *
 * **The idempotency key belongs to the attempt, not to the click.** It is created once when the form is opened and
 * reused by every retry of that attempt (P8). A key generated per click would make a retry of a request whose
 * response was lost into a *second* obligation - the failure mode idempotency keys exist to prevent - and the key
 * is why {@link worthRetrying} can be generous about a 503.
 *
 * **A 503 is retried once; a 422 is never.** The contract draws that line itself: a 422 means the request was
 * answerable and the answer is no, so asking again is load; a 503 means "not yet". The retry is safe rather than
 * hopeful, for the reason above.
 *
 * **The new row appears before the server has answered.** An obligations list that does not change until a round
 * trip completes makes a member press the button twice. So `onMutate` inserts a provisional row - marked as
 * pending, identified by the idempotency key - and `onError` removes it again. This is the plan's convention for
 * obligations ("optimistic + rollback"), and the rollback is not decoration: a refused cross-currency commitment
 * that stayed in the list would be a commitment the screen invented.
 *
 * **The row lands in the view the member is looking at**, which is the only view this code can name without
 * re-implementing the server's filtering. The filters arrive from the screen and the cache key is derived from the
 * same `obligationsQuery` the screen used, so there is one definition of "which list is this" rather than two that
 * have to agree. Any other view refetches when it is visited.
 */

export type RecordObligationVariables = {
  readonly values: RecordFormValues;
  /** Already parsed to integer minor units by `model/input`'s `amountOf`; this layer does not look at text. */
  readonly amount: Money;
  readonly idempotencyKey: string;
};

/** A row that exists in the browser and not yet on the server. */
export type PendingObligation = Obligation & { readonly pending: true };

export function isPending(obligation: Obligation): obligation is PendingObligation {
  return (obligation as PendingObligation).pending === true;
}

const listKey = (groupId: string) => ['obligations', groupId] as const;

export function useRecordObligation(groupId: string, filters: ObligationFilters) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (variables: RecordObligationVariables) =>
      request<Obligation>('/obligations', {
        method: 'POST',
        idempotencyKey: variables.idempotencyKey,
        body: {
          groupId,
          debtorId: variables.values.debtorId,
          creditorId: variables.values.creditorId,
          amount: variables.amount,
          ...(variables.values.settleInCurrency === '' ||
          variables.values.settleInCurrency === variables.amount.currency
            ? {}
            : { settleInCurrency: variables.values.settleInCurrency }),
          memo: variables.values.memo,
          occurredAt: variables.values.occurredAt,
        },
      }),

    retry: (attempt, fault) => attempt < 1 && worthRetrying(fault),

    onMutate: async (variables) => {
      const key = obligationsQuery(groupId, filters).queryKey;

      await client.cancelQueries({ queryKey: listKey(groupId) });

      const previous = client.getQueryData<Obligation[]>(key);

      // Only if the view is already loaded: inserting into a view nobody is looking at would be a cache write with
      // no reader.
      if (previous !== undefined) {
        const provisional: PendingObligation = {
          id: variables.idempotencyKey,
          groupId,
          debtorId: variables.values.debtorId,
          creditorId: variables.values.creditorId,
          amount: variables.amount,
          ...(variables.values.settleInCurrency === '' ||
          variables.values.settleInCurrency === variables.amount.currency
            ? {}
            : { settleInCurrency: variables.values.settleInCurrency }),
          memo: variables.values.memo,
          status: 'open',
          occurredAt: variables.values.occurredAt,
          pending: true,
        };

        client.setQueryData<Obligation[]>(key, [provisional, ...previous]);
      }

      return { key, previous };
    },

    onError: (_fault, _variables, context) => {
      if (context?.previous !== undefined) {
        client.setQueryData(context.key, context.previous);
      }
    },

    onSuccess: (recorded, variables, context) => {
      // The server's row replaces the provisional one in place, so the reader does not see the amount flicker -
      // and any other view, whose predicate the server has decided, refetches.
      if (context.previous !== undefined) {
        client.setQueryData<Obligation[]>(context.key, [
          recorded,
          ...context.previous.filter((obligation) => obligation.id !== variables.idempotencyKey),
        ]);
      }

      void client.invalidateQueries({ queryKey: listKey(groupId) });
    },
  });
}

/** A fresh attempt, with a fresh key. Called when a form is opened or after a successful record. */
export function nextAttemptKey(): string {
  return newIdempotencyKey();
}
