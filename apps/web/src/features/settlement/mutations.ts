import { useMutation, useQueryClient } from '@tanstack/react-query';

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

export type DecisionVariables = {
  readonly memberId: string;
  readonly decision: DecisionWord;
};

export function useDecide(groupId: string, runId: string) {
  const client = useQueryClient();

  return useMutation({
    mutationFn: (variables: DecisionVariables) =>
      request<RecordedDecision>(`/runs/${runId}/decisions`, {
        method: 'POST',
        // Spoken rather than defaulted: the contract's absent `decision` means approve, for the clients that sent
        // the older body, and a client that knows what it means says so.
        body: { memberId: variables.memberId, decision: variables.decision },
      }),

    onSuccess: () => {
      // Both reads, because both changed: the answer is on the run's record, and the group's page shows the run.
      void client.invalidateQueries({ queryKey: runQuery(runId).queryKey });
      void client.invalidateQueries({ queryKey: groupRunsQuery(groupId).queryKey });
    },
  });
}
