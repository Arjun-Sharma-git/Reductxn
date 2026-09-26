import { useCallback, useEffect, useState } from 'react';

/**
 * The theme, as a preference rather than a boolean.
 *
 * Three values, not two: `system` is the default and the one most users want, and a toggle that only knows
 * light/dark has to guess what "system" was and then cannot get back to following it. The resolved theme is
 * written to `data-theme` on the document element, which is where `tokens.css` reads it from - so there is one
 * place that decides and one place that renders.
 *
 * The pre-paint flash is avoided the way a single-page app can avoid it: `main.tsx` applies the stored
 * preference *before* the first render, so the first paint is already correct. A blocking inline script in
 * `index.html` would be marginally earlier and would put JavaScript in the document, which then has to be
 * reasoned about with any future Content-Security-Policy.
 */
export type ThemePreference = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'reductxn.theme';

export function storedPreference(): ThemePreference {
  const stored = globalThis.localStorage?.getItem(STORAGE_KEY);

  return stored === 'light' || stored === 'dark' ? stored : 'system';
}

function systemPrefersDark(): boolean {
  return globalThis.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

export function resolveTheme(preference: ThemePreference): 'light' | 'dark' {
  return preference === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : preference;
}

export function applyTheme(preference: ThemePreference): void {
  document.documentElement.dataset['theme'] = resolveTheme(preference);
}

export function useTheme(): { preference: ThemePreference; setPreference: (next: ThemePreference) => void } {
  const [preference, setPreferenceState] = useState<ThemePreference>(() => storedPreference());

  useEffect(() => {
    applyTheme(preference);
    globalThis.localStorage?.setItem(STORAGE_KEY, preference);
  }, [preference]);

  // Following the system means following it *while the app is open*: a user whose machine switches to dark at
  // sunset should not have to reload the page.
  useEffect(() => {
    if (preference !== 'system') {
      return;
    }

    const query = globalThis.matchMedia?.('(prefers-color-scheme: dark)');

    if (query === undefined) {
      return;
    }

    const listener = () => applyTheme('system');
    query.addEventListener('change', listener);

    return () => query.removeEventListener('change', listener);
  }, [preference]);

  const setPreference = useCallback((next: ThemePreference) => setPreferenceState(next), []);

  return { preference, setPreference };
}
