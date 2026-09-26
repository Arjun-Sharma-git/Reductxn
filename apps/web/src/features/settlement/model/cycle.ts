import type { Cycle } from '@/entities/cycle';
import { fromLocalInput } from '@/shared/lib/localTime';
import { ok, refuse, type Result } from '@/shared/lib/result';

/**
 * What a member typed to open a cycle, as a request - or the refusal that says what is wrong with it.
 *
 * Pure functions, no React, for the reason `features/obligations/model/input.ts` gives: the part of a form that can
 * be wrong is testable in milliseconds while a browser test takes seconds.
 *
 * **Only one thing can be wrong locally: the instant.** There is no check for "this group has nothing to settle",
 * and its absence is the decision rather than an omission. The API refuses nothing either (ADR 0033), for a reason
 * that is even stronger in a browser: a commitment recorded tomorrow can carry an instant inside a cycle opened
 * today, so a form that refused an "empty" instant would stop a member settling a backdated bill. The worker is what
 * refuses, and the sentence a member reads when it does is "asked, waiting since" in the list.
 *
 * The refusal code is the server's - `api.request_incomplete` - rather than a client invention, so one rule has one
 * message whether it was caught here or answered late (the practice `RecordFormValues` documents).
 */

export function openBody(asOf: string): Result<{ readonly asOf: string }> {
  const instant = fromLocalInput(asOf);

  if (instant === '') {
    return refuse('api.request_incomplete');
  }

  return ok({ asOf: instant });
}

/**
 * What a cycle's row means to a reader: a plan exists, or it does not.
 *
 * Two states rather than three, and the missing one is worth stating. A cycle with no plan is *not yet computed* if
 * it was asked for moments ago, and *not going to be* if the group had nothing recorded at or before its instant -
 * and nothing in the contract tells those apart, because telling them apart would mean the API recording a refusal
 * it does not make. So the second state is "waiting", the screen shows when the member asked (`createdAt`), and a
 * cycle waiting for days is visible as an anomaly rather than misreported as a state.
 */
export function cycleState(cycle: Cycle): 'waiting' | 'computed' {
  return cycle.runId === undefined || cycle.runId === null ? 'waiting' : 'computed';
}

/** The stable key for one row, so a list does not reorder itself between renders. */
export function cycleKey(cycle: Cycle): string {
  return cycle.id;
}
