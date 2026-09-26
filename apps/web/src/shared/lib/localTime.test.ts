import { describe, expect, it } from 'vitest';

import { fromLocalInput, toLocalInput } from './localTime';

/**
 * The one conversion at the edge between a wall clock and an instant.
 *
 * Both failure modes it prevents are silent, which is why it has a test rather than a comment: a form that sent the
 * local string unconverted would record an instant hours out, and one that stored the UTC string would show a member
 * a time that is not theirs.
 *
 * Every expectation here is computed in the reader's own zone - a round trip, or an instant built the same way the
 * module builds it - rather than written as a literal. That makes the test zone-independent, which matters for the
 * reason that it is also a limitation: a CI running at UTC+0 cannot distinguish a conversion that dropped the offset
 * from one that kept it, so the module documents what it does instead of trusting this file to catch everything.
 */
describe('localTime', () => {
  it('keeps the wall clock a member typed, in their own zone', () => {
    const local = '2026-09-30T23:59';
    const instant = fromLocalInput(local);

    // Whatever the zone, the two directions agree - and the instant is a UTC one, not a rewritten local string.
    expect(instant).toMatch(/Z$/);
    expect(toLocalInput(instant)).toBe(local);
  });

  it('moves the instant by the reader’s offset rather than treating it as UTC', () => {
    const instant = new Date('2026-09-30T23:59:00').toISOString();

    expect(fromLocalInput('2026-09-30T23:59')).toBe(instant);
  });

  it('answers an empty string for something that is not a date, rather than throwing', () => {
    // Both directions are used on values a member can leave blank, so neither may be the place a form crashes.
    expect(fromLocalInput('')).toBe('');
    expect(fromLocalInput('not a date')).toBe('');
    expect(toLocalInput('not a date')).toBe('');
  });

  it('loses seconds, because the control has no field for them', () => {
    // Stated in the module's own documentation as the input's precision rather than a rounding this code does. The
    // assertion is a property rather than a formatted string: the same instant, truncated to the minute the control
    // can show - which holds in every zone, where comparing against a literal would pass only at UTC+0.
    const instant = '2026-09-30T23:59:59Z';
    const shown = toLocalInput(instant);
    const parsed = new Date(shown);

    expect(parsed.getSeconds()).toBe(0);
    expect(new Date(instant).getTime() - parsed.getTime()).toBeLessThan(60_000);
    expect(new Date(instant).getTime() - parsed.getTime()).toBeGreaterThanOrEqual(0);
  });
});
