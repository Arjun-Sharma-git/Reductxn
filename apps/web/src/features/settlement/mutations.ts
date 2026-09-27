import { useMutation, useQueryClient } from '@tanstack/react-query';

import { groupCyclesQuery, type OpenedCycle, type OpenCycleBody } from '@/entities/cycle';
import { groupRunsQuery, runQuery, type RecordedDecision } from '@/entities/run';
import { request } from '@/shared/api/client';

/**
 * Recording a member's answer to a run.
 *
 * **No idempotency key, and this is the operation where that is right.** P8 asks every state-changing operation to
 * be idempotent, and the contract achieves it here by rule rather than by a key: a member has one answer per run, the
 * stored answer is never overwritten, and answering twice is a `409`. A key would add nothing a constraint does not
 * already guarantee - and unlike an obligation, there is no second row to create by mistake.
 *
 * **A lost response becomes a correct state after one more press.** If the first attempt was recorded and its
 * response was lost, the member presses again, the server answers `409 decision_already_answered`, and the screen
 * stops offering the buttons (`model/decision.ts`'s `alreadyAnswered`). So there is no retry policy here: the failure
 * a retry would hide is one the member can resolve by looking at the answer the message describes.
 *
 * **Nothing is written optimistically, and the reason is the difference between an answer and its effect.** The
 * new answer is not what a member is waiting to see - the run's *status* is, and that is the worker's judgement over
 * the answers rather than a row this client inserted. Putting a provisional answer in the cache would be this screen
 * claiming an approval counted, which is exactly what the contract refuses to say (ADR 0020, ADR 0021). So the
 * screen shows that the answer is being recorded, and then re-reads the run.
 */

export type DecisionWord = 'approve' | 'reject';

/**
 * The answer, and nothing about who is giving it.
 *
 * <p>There was a `memberId` here until ADR 0053, and the screen had a picker to fill it. It is gone rather than
 * defaulted: the caller is the answering member now, the contract's body has no field for one, and a client that
 * cannot name a member is a client that cannot answer as somebody else.
 */
export type DecisionVariables = {
  readonly decision: DecisionWord;
};

/**
 * Opening a settlement cycle: a member saying the group's books are closed at an instant.
 *
 * **No idempotency key, and here that is a property of the operation rather than a decision about retries.** P8 asks
 * every state-changing operation to be idempotent, and opening a cycle is idempotent on `(group, asOf)` by
 * construction: the pair is a unique constraint, a second request for the same instant answers `200` with the cycle
 * that already exists, and a retry whose response was lost collides with the row the first attempt wrote rather than
 * opening a second settlement. The instant is required by the contract for the same reason - a default of "now"
 * would make two clicks two different cycles - so there is nothing here for a key to add.
 *
 * **Nothing is written optimistically.** Whether this call opened the cycle or found it, and whether a plan exists
 * over it, are the server's answers, and a row invented in the cache would be this screen guessing at both. What the
 * member gets instead is the answer: the cycles card re-reads the list, which is where "asked, waiting since" comes
 * from.
 */
export function useOpenCycle(groupId: string) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (variables: OpenCycleBody) =>
      request<OpenedCycle>('/groups/' + groupId + '/cycles', { method: 'POST', body: variables }),

    onSuccess: () => {
      // The cycles list is what changed, and only it. The group's runs cannot have changed: a plan over a cycle the
      // worker has never seen does not exist yet, so invalidating that read would be a refetch that returns the same
      // page (ADR 0034).
      void client.invalidateQueries({ queryKey: groupCyclesQuery(groupId).queryKey });
    },
  });
}

export function useDecide(groupId: string, runId: string) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (variables: DecisionVariables) =>
      request<RecordedDecision>(`/runs/${runId}/decisions`, {
        method: 'POST',
        // Spoken rather than defaulted: the contract's absent `decision` means approve, for the clients that sent
        // the older body, and a client that knows what it means says so. The member is not in this body at all since
        // ADR 0053 - the session is who is answering, and this client has no way to say otherwise.
        body: { decision: variables.decision },
      }),

    onSuccess: () => {
      // Both reads, because both changed: the answer is on the run's record, and the group's page shows the run.
      void client.invalidateQueries({ queryKey: runQuery(runId).queryKey });
      void client.invalidateQueries({ queryKey: groupRunsQuery(groupId).queryKey });
    },
  });
}
