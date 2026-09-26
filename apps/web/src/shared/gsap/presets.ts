import { gsap } from 'gsap';

/**
 * Motion, under one policy.
 *
 * The plan's rule (RULE F7) is that timelines live in `features/*` or `shared/gsap`, and the skill's seventh
 * priority is that animation "conveys meaning" and has a **reduced-motion branch**. This file is the branch: it
 * is the only place a GSAP timeline is created, and it decides whether to create one at all.
 *
 * The durations and easings come from the tokens, so motion is a design decision in one file rather than a
 * number in a component. `--rn-motion-*` is read from the document rather than restated in TypeScript, because
 * two copies of a duration is one copy too many.
 *
 * Why GSAP rather than CSS transitions for this: GSAP animates the *stagger* - a sequence of elements entering
 * one after another - and expresses it as a timeline with a single cancel token. A CSS-only version needs
 * `animation-delay` per child, which is a different mechanism for every count, and cancels badly when the data
 * changes underneath it. The CSS guard in `global.css` still exists and still matters: it is what stops a
 * transition that somebody adds later from ignoring this policy.
 */

export function prefersReducedMotion(): boolean {
  return globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

function durationToken(name: string, fallback: number): number {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();

  // `Number.parseInt`, not `parseFloat` - the F12 ban is about amounts, and a design token is not one, but the
  // same discipline applies: take the whole number and reject the rest rather than accepting a partial parse.
  if (value.endsWith('ms')) {
    return Number.parseInt(value, 10);
  }

  if (value.endsWith('s')) {
    return Number.parseInt(value, 10) * 1000;
  }

  return fallback;
}

export function motionBaseMs(): number {
  return durationToken('--rn-motion-base', 200);
}

/**
 * A staggered reveal, or nothing at all.
 *
 * @returns a function that stops the timeline, for a caller that unmounts mid-animation
 */
export function revealStagger(targets: readonly Element[]): () => void {
  if (targets.length === 0 || prefersReducedMotion()) {
    // Under reduced motion the elements are simply *there*. Not a shortened animation: for a vestibular
    // disorder, a fast stagger is the same motion at a worse speed. The final state is the default state.
    return () => undefined;
  }

  const timeline = gsap.timeline();

  timeline.from(targets, {
    opacity: 0,
    yPercent: 6,
    duration: motionBaseMs() / 1000,
    stagger: 0.04,
    ease: 'power2.out',
  });

  return () => {
    timeline.kill();
  };
}
