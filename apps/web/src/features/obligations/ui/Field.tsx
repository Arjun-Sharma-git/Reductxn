import type { ReactNode } from 'react';

import { useTranslations } from '@/shared/i18n';

import type { Refusal } from '../model/input';
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
  readonly refusal?: Refusal | undefined;
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
