import { useEffect, type RefObject } from 'react';

import { revealStagger } from '@/shared/gsap/presets';

/**
 * The screen's motion, kept in the feature that owns it (RULE F7).
 *
 * One stagger, on the rows of the plan, and its meaning is grouping rather than decoration: the rows arrive
 * together because they are one answer to one question, and a settlement report that materialises all at once
 * makes a reader wonder whether it is finished loading. `key` re-runs it when the data changes, so a preview for
 * a different instant does not silently re-use the previous arrangement.
 *
 * The reduced-motion decision belongs to `shared/gsap/presets`, so this file has no `matchMedia` in it - a
 * feature that decided for itself would be the fourth place with an opinion about motion.
 */
export function useStaggerReveal(container: RefObject<HTMLElement | null>, key: unknown): void {
  useEffect(() => {
    const root = container.current;

    if (root === null) {
      return;
    }

    return revealStagger([...root.querySelectorAll('[data-reveal]')]);
  }, [container, key]);
}
