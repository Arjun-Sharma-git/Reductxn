import { describe, expect, it } from 'vitest';

import type { Cycle } from '@/entities/cycle';

import { cycleState, openBody } from './cycle';

/**
 * What a member's cut-off becomes, and what a cycle's row means.
 *
 * Neither function needs a server or a browser, and both have a decision in them: the instant is converted at the
 * edge (a local wall clock on the wire is an instant hours out), and a cycle with no plan is "waiting" rather than a
 * guess at why.
 */
const cycle = (runId: string | null | undefined): Cycle => ({
  id: '66666666-6666-4666-8666-666666666666',
  groupId: '3f7c1c1e-0d2a-4a5e-9a6f-6c1d2f4b8e10',
  asOf: '2026-09-30T23:59:59Z',
  createdAt: '2026-09-30T12:00:00Z',
  ...(runId === undefined ? {} : { runId }),
});

describe('openBody', () => {
  it('turns the member’s wall clock into the instant the request carries', () => {
    const result = openBody('2026-09-30T23:59');

    expect(result.ok).toBe(true);
    expect(result.ok && result.value.asOf).toMatch(/Z$/);
    expect(result.ok && new Date(result.value.asOf).getTime()).toBe(new Date('2026-09-30T23:59').getTime());
  });

  it('refuses an empty or unreadable instant with the server’s own code', () => {
    // The code belongs to the server, and it is used here so that one rule has one message whether the form caught
    // it or the request did.
    for (const value of ['', 'yesterday']) {
      const result = openBody(value);

      expect(result.ok).toBe(false);
      expect(!result.ok && result.code).toBe('api.request_incomplete');
    }
  });

  it('does not refuse an instant with nothing recorded at it', () => {
    // The absence of this check is the decision: a commitment recorded tomorrow can carry an instant inside a cycle
    // opened today, so a form that refused an "empty" instant would stop a member settling a backdated bill. The
    // worker refuses when it computes, and the list says "waiting" rather than this form guessing.
    expect(openBody('2026-09-30T23:59').ok).toBe(true);
  });
});

describe('cycleState', () => {
  it('is waiting until a plan exists', () => {
    expect(cycleState(cycle(undefined))).toBe('waiting');
    expect(cycleState(cycle(null))).toBe('waiting');
  });

  it('is computed once the row carries a plan', () => {
    expect(cycleState(cycle('77777777-7777-4777-8777-777777777777'))).toBe('computed');
  });
});
