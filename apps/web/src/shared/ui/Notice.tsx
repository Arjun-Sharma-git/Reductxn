import type { ReactNode } from 'react';

import { useTranslations } from '@/shared/i18n';
import { problemOf } from '@/shared/api/client';

import styles from './Notice.module.css';

/**
 * Two kinds of notice, deliberately different shapes, because they are different things:
 *
 *   - {@link Notice} is a statement about the page's state - "this is shown in English because the Russian
 *     translation has not been reviewed" - and is rendered inside the page, where the reader looks.
 *   - {@link ProblemNotice} is an API refusal, and it renders the **code** through the `errors` catalog rather
 *     than the server's English `title`. That is RULE B20 from the client's side: the server sends a code and
 *     parameters, the client localizes, and a new language therefore needs no backend release.
 *
 * Neither is a toast. A toast disappears, and a user who looked away has to guess what happened - which for a
 * settlement screen is not acceptable.
 */
export function Notice({
  tone = 'info',
  children,
}: {
  readonly tone?: 'info' | 'warning';
  readonly children: ReactNode;
}) {
  return (
    <p className={tone === 'warning' ? styles.warning : styles.info} role="status">
      {children}
    </p>
  );
}

/**
 * An API failure, in the reader's language.
 *
 * The `errors` namespace is keyed by the server's code, and a code the catalog does not know renders as a
 * generic sentence *plus* the code - never as nothing. A user seeing `netting.preview_failed (fx.rate_unavailable)`
 * is a user who can tell support which failure it was; a user seeing "Something went wrong" is not.
 */
export function ProblemNotice({ fault }: { readonly fault: unknown }) {
  const { t } = useTranslations();
  const problem = problemOf(fault);
  const known = t(`errors.${problem.code}`);

  const message = known === `errors.${problem.code}`
    ? t('errors.client.unmapped', { code: problem.code })
    : known;

  return (
    <p className={styles.warning} role="alert">
      {message}
    </p>
  );
}
