import type { ReactNode } from 'react';

import { useTranslations } from '@/shared/i18n';

import styles from './Field.module.css';

/**
 * A labelled field with its error and its hint, in the order a person reads them.
 *
 * The skill's eighth priority is one sentence long - the error belongs beside the field - and it is the difference
 * between a form that explains itself and one that makes a member hunt for the reason it refused. The label is
 * always visible (never a placeholder), the error is announced (`role="alert"`), and the control is marked
 * `aria-invalid` and pointed at the message's id so a screen reader reads the reason with the field.
 *
 * This is a primitive rather than a component library entry: it exists because five fields on one screen need the
 * same four lines of structure, and because getting `aria-describedby` wrong once is enough to make a form
 * unusable with a screen reader.
 *
 * **It moved here when a second feature wanted it** (ADR 0035), which is this module's own rule: a feature's
 * internals are private (RULE F8), so the choice was to copy it into `features/members` or to let both screens share
 * it, and the second screen is what this barrel was waiting for. The refusal prop is now structural - `{ code }` -
 * rather than an obligations type, so a feature can pass its own refusal value without this module depending on it.
 *
 * The message is looked up by code alone. A refusal may carry parameters, and this control does not render them: the
 * catalog's sentences are written to read correctly without them, which is what makes "one code, one sentence" true
 * across screens.
 */
export function Field({
  id,
  label,
  hint,
  refusal,
  children,
}: {
  readonly id: string;
  readonly label: string;
  readonly hint?: string;
  readonly refusal?: { readonly code: string } | undefined;
  readonly children: ReactNode;
}) {
  const { t } = useTranslations();

  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={id}>
        {label}
      </label>

      {hint === undefined ? null : <p className={styles.hint}>{hint}</p>}

      {children}

      {refusal === undefined ? null : (
        <p className={styles.error} id={`${id}-error`} role="alert">
          {t(`errors.${refusal.code}`)}
        </p>
      )}
    </div>
  );
}
