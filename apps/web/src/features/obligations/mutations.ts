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

/**
 * Voiding an obligation: recording that a commitment should never have been written.
 *
 * **No idempotency key, and that is the server's decision rather than an omission here** (ADR 0045). The header is
 * gone from the contract because `voided_at` already answers whether the row was voided: the server's update is
 * guarded by `voided_at IS NULL`, so a retry - including the one `worthRetrying` allows - is a retry of an attempt
 * that either happened or did not, and never a second claim about when it happened. Adding a key here would make
 * the client send a header the contract no longer declares.
 *
 * **The row changes status before the answer arrives, and every view of the list is patched.** The recording path
 * could name exactly one cache entry because a new row only matches the filters it was recorded under; a void
 * changes a row that is already in every view it appears in, so a partial-key update reaches all of them at once -
 * a member looking at `?status=open` and a member looking at `?status=voided` are looking at the same commitment.
 *
 * **A refusal puts the row back.** A `409` (`api.obligation_not_open`) means a settlement run holds the commitment,
 * and a row that stayed `voided` after that would be the screen inventing a correction the server refused - the
 * failure mode the recording path's rollback exists to prevent, in the other direction.
 */
export function useVoidObligation(groupId: string) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (obligationId: string) =>
      // The id is the whole request: the contract declares no body for this operation, so there is nothing to send.
      request<Obligation>(`/obligations/${obligationId}/void`, { method: 'POST' }),

    retry: (attempt, fault) => attempt < 1 && worthRetrying(fault),

    onMutate: async (obligationId) => {
      await client.cancelQueries({ queryKey: listKey(groupId) });

      const previous = client.getQueriesData<Obligation[]>({ queryKey: listKey(groupId) });

      client.setQueriesData<Obligation[]>({ queryKey: listKey(groupId) }, (rows) =>
        rows?.map((obligation) =>
          obligation.id === obligationId ? { ...obligation, status: 'voided' as const } : obligation,
        ),
      );

      return { previous };
    },

    onError: (_fault, _obligationId, context) => {
      for (const [key, rows] of context?.previous ?? []) {
        client.setQueryData(key, rows);
      }
    },

    onSuccess: (voided) => {
      // The server's row replaces the optimistic one, so a status the client guessed is corrected by the one the
      // server decided - and anything else holding this obligation (the netting preview, the balances) refetches.
      client.setQueriesData<Obligation[]>({ queryKey: listKey(groupId) }, (rows) =>
        rows?.map((obligation) => (obligation.id === voided.id ? voided : obligation)),
      );

      void client.invalidateQueries({ queryKey: listKey(groupId) });
      // The keys are the ones the readers actually use, read from their own query files rather than guessed: the
      // preview is `['netting', 'preview', groupId, asOf]` and the balances are `['groups', groupId, 'balances']`.
      // A prefix reaches every instant a preview was computed for, because a voided commitment is voided in all of
      // them (ADR 0036's derivation excludes it from open ones), not only in the view that was on screen.
      void client.invalidateQueries({ queryKey: ['netting', 'preview', groupId] });
      void client.invalidateQueries({ queryKey: ['groups', groupId, 'balances'] });
    },
  });
}
