import { describe, expect, it } from 'vitest';

import { ApiProblemError } from '@/shared/api/client';

import { alreadyAnswered, isFinished, isKnownStatus, toneOf } from './decision';

/**
 * The status vocabulary and the one refusal that changes what the screen offers.
 *
 * The interesting assertions are the two about a word this screen has not been told: the migration's list can grow,
 * and the read model reports whatever the worker wrote rather than only the states it recognises - so the client has
 * to have an answer for a ninth status rather than printing it as if it were a phrase.
 */
describe('what a run’s status looks like to a reader', () => {
  it('has a tone for every status the migration names', () => {
    // The eight from V501's CHECK constraint, which is the vocabulary's home.
    for (const status of [
      'pending',
      'computing',
      'awaiting_approval',
      'awaiting_fx_funding',
      'executing',
      'done',
      'rejected',
      'failed',
    ]) {
      expect(isKnownStatus(status)).toBe(true);
      expect(toneOf(status)).not.toBe('unknown');
    }

    expect(toneOf('awaiting_approval')).toBe('waiting');
    expect(toneOf('done')).toBe('done');
    expect(toneOf('failed')).toBe('stopped');
  });

  it('says it does not know rather than showing an identifier when the worker has learned a new state', () => {
    expect(isKnownStatus('reconciling')).toBe(false);
    expect(toneOf('reconciling')).toBe('unknown');
  });

  it('recognises the end of a run without claiming the answer counted', () => {
    expect(isFinished('done')).toBe(true);
    expect(isFinished('rejected')).toBe(true);
    expect(isFinished('awaiting_approval')).toBe(false);
  });
});

describe('a refusal from the decision endpoint', () => {
  const problem = (code: string, status: number) => new ApiProblemError({ code, status, params: {}, title: code });

  it('recognises the one refusal that means stop asking', () => {
    // The contract: "a member has one answer per run ... and the stored answer is never overwritten".
    expect(alreadyAnswered(problem('api.decision_already_answered', 409))).toBe(true);
  });

  it('does not treat a malformed request or a missing run as an answered one', () => {
    expect(alreadyAnswered(problem('api.request_incomplete', 400))).toBe(false);
    expect(alreadyAnswered(problem('api.no_such_run', 404))).toBe(false);
    expect(alreadyAnswered(new TypeError('Failed to fetch'))).toBe(false);
  });
});

describe('a refusal from the decision endpoint', () => {
  const problem = (code: string, status: number) => new ApiProblemError({ code, status, params: {}, title: code });

  it('recognises the one refusal that means stop asking', () => {
    // The contract: "a member has one answer per run ... and the stored answer is never overwritten".
    expect(alreadyAnswered(problem('api.decision_already_answered', 409))).toBe(true);
  });

  it('does not treat a malformed request or a missing run as an answered one', () => {
    expect(alreadyAnswered(problem('api.request_incomplete', 400))).toBe(false);
    expect(alreadyAnswered(problem('api.no_such_run', 404))).toBe(false);
    expect(alreadyAnswered(new TypeError('Failed to fetch'))).toBe(false);
  });
});
