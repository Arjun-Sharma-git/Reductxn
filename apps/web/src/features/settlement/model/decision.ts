import { problemOf } from '@/shared/api/client';

/**
 * What a run's status means to a reader, and what the server says when an answer cannot be recorded.
 *
 * <p><strong>Two things this file deliberately does not do.</strong> It does not decide whether an answer would
 * *count* - the run's own arithmetic does that, in the worker, and a client that greyed out an approval because it
 * had counted the answers would be a second implementation of RULE S1 (ADR 0021). And it does not hide the answer
 * buttons based on a status: the API records the answer and the run decides what it means, so a screen that
 * refused to *offer* the answer would be inventing a rule to save a round trip.
 *
 * <p>What it does do is turn the refusal codes into a decision, and the status into a colour. The status *word* is not
 * here at all: it is `settlement.status.<status>`, keyed by the status itself, so the vocabulary's one home stays the
 * migration's `CHECK` constraint and a component can ask for
 * `` t(`settlement.status.${status}`) `` without a second copy of the list in a map. (The first version of this file
 * *did* keep a key per status and the locale gate caught it: `I2` cannot see a key that only exists inside a table,
 * and the check was right - a table of keys is a second place the vocabulary has to be kept correct.)
 */

export type Tone = 'waiting' | 'working' | 'done' | 'stopped' | 'unknown';

const TONES: Readonly<Record<string, Tone>> = {
  pending: 'working',
  computing: 'working',
  awaiting_approval: 'waiting',
  awaiting_fx_funding: 'waiting',
  executing: 'working',
  done: 'done',
  rejected: 'stopped',
  failed: 'stopped',
};

/** Whether this screen has a word for the status, which is different from whether the run is answerable. */
export function isKnownStatus(status: string): boolean {
  return status in TONES;
}

/** What the status is doing, for the badge's colour - presentation, with no rule behind it. */
export function toneOf(status: string): Tone {
  return TONES[status] ?? 'unknown';
}

/**
 * Whether the server refused because this member has already answered.
 *
 * <p>The one refusal that changes what the screen offers rather than what it says. The contract is explicit that an
 * answer is never overwritten ("a member has one answer per run: answering twice is a `409`, and the stored answer
 * is never overwritten"), so a screen that kept offering the buttons after this refusal would be inviting a member
 * to do something the system will not do. The message is still the server's - this only decides to stop asking.
 */
export function alreadyAnswered(fault: unknown): boolean {
  return problemOf(fault).code === 'api.decision_already_answered';
}

/** The run's own last word, which is what a member reads after answering rather than a claim about their answer. */
export function isFinished(status: string): boolean {
  return status === 'done' || status === 'rejected' || status === 'failed';
}
