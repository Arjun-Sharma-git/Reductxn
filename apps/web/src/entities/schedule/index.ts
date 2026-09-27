import { queryOptions } from '@tanstack/react-query';

import { request, type paths } from '@/shared/api/client';

/**
 * A group's schedule: when its books close, and how often after that (ADR 0057).
 *
 * **It is the read that makes the settlement worker's cadence visible.** A group states a cadence through an operation
 * and the worker honours it on its own tick; before this entity existed, nothing in the client could say when that would
 * happen, and a group's cycles appeared - if they appeared at all - because somebody had asked for one by hand.
 *
 * **The instant is read from the row rather than derived from the interval.** {@code nextDueAt} is what the worker will
 * act on and {@code setAt} says how current the statement is; a client that computed one from the other two would be a
 * second implementation of the cadence's arithmetic, and the first period a worker missed would make the two disagree.
 */

/** When a group's books close by themselves: the interval, the next due instant, and when it was last set. */
export type CycleSchedule = paths['/groups/{groupId}/schedule']['get']['responses']['200']['content']['application/json'];

/** What a group states: the same two facts without the server's stamp. */
export type NewSchedule = NonNullable<
  paths['/groups/{groupId}/schedule']['put']['requestBody']
>['content']['application/json'];

/**
 * The group's schedule, asked by a member.
 *
 * **`retry: false`, and here one of the failures is routine.** A group that has not stated a cadence answers
 * {@code 404 api.no_such_schedule} - which is a state the screen renders as "the books close when somebody asks",
 * not as a fault - and asking three times changes none of the answers. The same argument the other group reads make.
 */
export function groupScheduleQuery(groupId: string) {
  return queryOptions({
    queryKey: ['groups', groupId, 'schedule'] as const,
    queryFn: () => request<CycleSchedule>('/groups/' + groupId + '/schedule'),
    retry: false,
  });
}