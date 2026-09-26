import type { ReactNode } from 'react';

import styles from './Card.module.css';

/**
 * A surface with a title, because every panel on these screens is one.
 *
 * `<section>` with a heading rather than a `<div>` with a big font: a screen reader's heading list is how somebody
 * navigates a report, and a title that is only visually a title is missing from it. The `<h2>` is the section's,
 * so the page owns the `<h1>` and a card never competes with it.
 */
export function Card({
  title,
  description,
  children,
}: {
  readonly title: ReactNode;
  readonly description?: ReactNode;
  readonly children: ReactNode;
}) {
  return (
    <section className={styles.card}>
      <header className={styles.header}>
        <h2 className={styles.title}>{title}</h2>
        {description === undefined ? null : <p className={styles.description}>{description}</p>}
      </header>
      {children}
    </section>
  );
}
