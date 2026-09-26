import { useTranslations } from '@/shared/i18n';

import styles from './AppHeaderControls.module.css';
import { useTheme, type ThemePreference } from './theme';

/**
 * The two document-level preferences, in the header.
 *
 * Both are `<fieldset>`/`<select>`-shaped rather than click-to-toggle, and that is the skill's first priority
 * category doing the work: a toggle button labelled with an icon has no accessible name, no announced state, and
 * is impossible to operate with a keyboard in the way a native control is. These are native controls with
 * visible labels, which is why there are no icons and no `aria-label` gymnastics in this file.
 *
 * The theme control offers `system` as well as `light` and `dark`, because a preference that cannot express "do
 * what my machine does" is a preference the user has to re-set on every device.
 */
export function AppHeaderControls({ availableLocales }: { readonly availableLocales: readonly string[] }) {
  const { t, locale } = useTranslations();
  const { preference, setPreference } = useTheme();

  return (
    <div className={styles.controls}>
      <label className={styles.field}>
        <span className={styles.label}>{t('common.language')}</span>
        <select
          className={styles.select}
          value={locale}
          onChange={(event) => {
            // A full navigation rather than a state change: the locale is part of the page's identity
            // (`docs/specs/i18n.md` section 3), so a link shared by a Russian reader opens in Russian.
            const url = new URL(globalThis.location.href);
            url.searchParams.set('lang', event.target.value);
            globalThis.location.assign(url.toString());
          }}
        >
          {availableLocales.map((code) => (
            <option key={code} value={code}>
              {t(`common.localeName.${code}`)}
            </option>
          ))}
        </select>
      </label>

      <label className={styles.field}>
        <span className={styles.label}>{t('common.theme')}</span>
        <select
          className={styles.select}
          value={preference}
          onChange={(event) => setPreference(event.target.value as ThemePreference)}
        >
          <option value="system">{t('common.themeSystem')}</option>
          <option value="light">{t('common.themeLight')}</option>
          <option value="dark">{t('common.themeDark')}</option>
        </select>
      </label>
    </div>
  );
}
